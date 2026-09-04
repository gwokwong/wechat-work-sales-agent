# 企业微信销售 Agent（WeChat Sales Agent）

基于**企业微信官方会话存档方案**的单体 Spring Boot 项目骨架与设计文档。
读取企微客户聊天（会话存档）→ 维护客户上下文画像 → 识别销售阶段 → 按回复策略自动生成消息 → 人工闸门确认 → 外发，报价等动作对接已有业务系统（SPI）。

- 技术栈：Java 17 + Spring Boot 3.2.5 + Maven + Spring Data JPA
- 持久化：默认 H2 内存库（演示零依赖）；生产可切 MySQL（提供 `schema.sql`）
- LLM：`LLMClient` 抽象接口 + `MockLLMClient`（演示无需真实模型 Key）
- 企微通道：`Channel` 抽象 + `MockChannel`（每分钟注入一条模拟客户消息）+ `WeComChannel`（真实接入占位，留 TODO）

## 模块架构

```
                        ┌──────────────────────────────┐
                        │        REST 管理端             │
                        │  AdminController (/api)      │
                        │  客户/阶段/草稿审批/日志/手动发送 │
                        └──────────────┬───────────────┘
                                       │
┌─────────────┐   收消息   ┌───────────▼──────────────────────────┐
│  Channel 层  │──────────▶│       SalesAgentOrchestrator        │
│ MockChannel  │           │  (MessageListener 订阅 MessageBus)   │
│ WeComChannel │◀────┐     └───┬────────┬────────┬───────┬───────┘
└──────┬───────┘     │外发   │   │   │   │
       │             │        ▼       ▼        ▼       ▼
       ▼             │   ┌────────┐ ┌────────┐ ┌───────────┐ ┌──────────┐
┌─────────────┐      │   │Context │ │ Stage  │ │ Strategy  │ │Compliance│
│ MessageBus  │      └────┤Store / │ │ 分类器  │ │Service    │ │ Filter   │
│ (幂等去重)   │            │Profile │ │+状态机   │ │+模板渲染   │ │(合规校验) │
└─────────────┘            └───┬────┘ └───┬────┘ └─────┬─────┘ └──────────┘
                               │          │            │
┌───────────────┐              ▼          ▼            ▼
│  JPA / DB     │◀─── CustomerProfile / Deal / ReplyDraft / MessageLog / ActionLog
│ H2 / MySQL    │
└───────────────┘
                                ┌────────────────────┐
                                │ QuoteService (SPI) │ 报价对接业务系统（Mock 实现）
                                └────────────────────┘
```

## M0：零依赖演示跑法

前置：JDK 17+、Maven 3.8+（无真实企微账号、无 MySQL、无 LLM Key）。

```bash
cd wechat-sales-agent
mvn spring-boot:run                      # 默认 profile=demo：H2 内存库 + Mock 通道 + Mock LLM + 人工审批
# 或显式指定：mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

启动后每分钟 `MockChannel` 自动注入一条预置客户消息，触发完整链路：
**收消息 → 建档 → 落库 → 组装上下文 → 阶段分类 → 状态机推进 → 画像沉淀 → 策略选型 → 模板+MockLLM 润色 → 合规校验 → 写入 reply_draft(PENDING) 等人工审批**。

### 演示 curl 序列

```bash
# 1) 看客户列表
curl http://localhost:8080/api/customers

# 2) 看某客户完整上下文（画像+阶段+最近对话摘要）
curl http://localhost:8080/api/customers/1/context

# 3) 看商机与阶段
curl http://localhost:8080/api/deals

# 4) 查看待审批话术（等 Mock 注入至少一条后）
curl http://localhost:8080/api/drafts/pending

# 5) 人工确认并发送（人工闸门）
curl -X POST http://localhost:8080/api/drafts/1/approve

# 6) 看发送/审计日志
curl http://localhost:8080/api/logs

# 7) 手动推进状态机（如推进到 NEGOTIATION 验证话术策略切换）
curl -X POST http://localhost:8080/api/deals/1/transition \
  -H 'Content-Type: application/json' -d '{"stage":"NEGOTIATION"}'

# 8) 手动注入一条客户消息（可指定 external_userid 与内容）
curl -X POST http://localhost:8080/api/demo/inject \
  -H 'Content-Type: application/json' \
  -d '{"externalUserId":"wxid_demo_001","content":"你们的报价方案我们看过了，价格还能再谈吗？"}' \
  && curl http://localhost:8080/api/drafts/pending

# 9) 演示状态看板
curl http://localhost:8080/api/demo/status
```

> Mock 剧本默认演示到「方案/报价→商务谈判→成交（WON）」路线；自动消息约 15 秒后注入第一条，之后每分钟一条。
> 若希望手动逐条播放剧本：`curl -X POST http://localhost:8080/api/demo/inject-next`

### 切换 MySQL（application-mysql.yml 已备好）

```bash
# 1. 建库（schema.sql 已提供）
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS wechat_sales DEFAULT CHARACTER SET utf8mb4;"
mysql -uroot -p wechat_sales < src/main/resources/schema.sql

# 2. 修改 application-mysql.yml 中的账号密码后启动
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

## M1：接入真实企微的步骤清单

> 真实接入**必须**使用企业微信官方"会话内容存档"API（受监管接口，需企业认证并配置存档私钥/公钥）。
> 本项目已预留 `WeComChannel` / `WeComApiClient` 占位与全部配置项注释；接入点搜索 `TODO` 定位。

1. **企业侧准备**
   - 企业微信管理后台开通「会话内容存档」并购买对应席位；拿到 `corpId`。
   - 生成并配置会话存档专用 **公钥/私钥对**（私钥保存在本服务，公钥上传企微后台）。
   - 后台配置回调 URL（消息/会话回调）与 Token/EncodingAESKey。
   - 为应用分配 `secret`（会话存档 secret / 通讯录 secret）与可调用 API 的 IP 白名单。

2. **配置项填写**（`application-mysql.yml` / `application-prod.yml`，字段注释见 `WeComApiClient`）
   - `wecom.corp-id` / `wecom.secret` / `wecom.callback.token` / `wecom.callback.aes-key`
   - `wecom.archive.private-key`（会话存档私钥路径） / `wecom.archive.sdk-proxy`（如使用官方 SDK 代理）

3. **通道切换**
   - `app.channel.active: wecom`（M0 为 `mock`）；`wecom.agent` 设置对外发送所用应用 agentId。

4. **实现消息拉取与解密**（`WeComChannel` / 新增 `ArchivePuller` 定时任务）
   - 用会话存档 secret 换取 access_token；分页拉取 `getchatdata` 增量消息（`seq` 游标持久化）。
   - 用官方 SDK（`WeWorkFinanceSdk`，Java 通过 JNI/SDK 代理调用）或自研解密（AES-256-GCM，需 RSA 私钥解出会话密钥）还原明文消息。
   - 消息结构映射到 `Message`（msgId / externalUserId / content / msgType），推入 `MessageBus`（幂等见下）。

5. **回调接入（5 秒 ack + 异步处理）**
   - 新增回调 `@RestController`（`/wecom/callback`），校验签名与解密，业务上**立即返回**并异步投递到 `MessageBus`。
   - **禁止在回调线程内做长耗时**（解密/LLM/DB 写）：本项目编排器挂在消息总线监听，天然解耦。
   - `msgId` 幂等去重：`message_log.msg_id` 唯一索引 + 编排器先 `existsByMsgId` 再落库，重复回调不重复建草稿/不重复发送。

6. **外发能力**
   - `OutboundSender` 保持"读待发送表 + 找 active 通道发送"逻辑不变，仅替换 `WeComApiClient.sendTextMessage()` 实现（调用企微「客户联系-发送应用消息」API 或服务号消息）。
   - 企业微信外部联系人主动消息有 48 小时窗口等限制，需在策略层约束（TODO 注释已标）。

7. **生产化开关**
   - 频率限制：`MessageBus` 通道侧对客户做令牌桶限频（TODO：`RateLimiter` 接入）。
   - 审批模式：生产建议保持 `MANUAL`；测试可 `app.approval-mode: AUTO`。
   - 切换 LLM：实现 `LLMClient` 接入内部大模型服务（如混元/DeepSeek 等），替换 `MockLLMClient` Bean 即可，编排器不改。

## 对接自有业务系统（SPI 设计）

- **报价**：`com.example.wechatsales.action.QuoteService` 接口
  - `QuoteResult createQuote(QuoteRequest)` → 实现类对接自有报价/CRM 系统（HTTP/消息队列均可）；`MockQuoteService` 为演示实现（返回模拟报价号）。
  - 编排器命中 `CREATE_QUOTE` 动作策略时自动调用，报价号写入草稿 `quoteReference` 并渲染进话术（`【报价单 ${quoteReference}】`）。
- **人工闸门**：草稿表 `reply_draft` + REST 审批接口，可与业务系统审批流打通（轮询/回调）。
- **扩展动作**：在 `StrategyConfig.actionType` 增加枚举值并在编排器中加分支，即可扩展"创建任务/同步 CRM/查库存"等动作。
- **客户画像/上下文**：`CustomerProfileService` / `ContextStore` 提供内嵌 API，也可对外暴露 REST（`/api/customers/{id}/context`）供业务系统查询。

## 目录结构速览

```
wechat-sales-agent/
├── pom.xml
├── DESIGN.md
└── src/main/
    ├── java/com/example/wechatsales/
    │   ├── WechatSalesApplication.java
    │   ├── config/AppProperties.java, DemoDataInitializer.java
    │   ├── channel/Channel.java, MessageBus.java, MessageListener.java,
    │   │         Message.java, OutboundMessage.java, SendResult.java,
    │   │         MockChannel.java, WeComChannel.java, WeComApiClient.java,
    │   │         OutboundSender.java
    │   ├── context/ContextStore.java, ContactContext.java, ContextAssemblyService.java,
    │   │           CustomerProfileService.java
    │   ├── stage/StageClassifier.java, RuleStageClassifier.java, StageMachine.java,
    │   │         DealService.java
    │   ├── strategy/ReplyStrategy... / StrategyService.java, LLMClient.java,
    │   │             MockLLMClient.java, ComplianceFilter.java,
    │   │             DraftApprovalService.java, SalesAgentOrchestrator.java
    │   ├── action/QuoteService.java, MockQuoteService.java, QuoteResult.java,
    │   │          QuoteRequest.java, ActionLogger.java
    │   ├── domain/  (实体 + 枚举 + Repository)
    │   ├── rest/AdminController.java, ApiResponse.java
    │   └── exception/
    └── resources/
        ├── application.yml, application-demo.yml, application-mysql.yml
        ├── schema.sql, data.sql
        └── strategies/default.json
```

## 关键设计结论

- 销售阶段状态机：`LEAD_INITIAL → QUALIFIED → NEEDS_ANALYSIS → PROPOSAL → NEGOTIATION → WON/LOST`（详见 DESIGN.md §3 跃迁矩阵）。
- 上下文=短期最近 N 轮（`context_recent_size` 默认 10）+ 长期画像（需求摘要/预算/时间窗）+ 商机阶段，三者在每次消息后刷新。
- 安全闸门不破坏演示：默认 `MANUAL` 审批，任何 AI 话术必须人工确认才外发；敏感词/违规词命中自动 `BLOCKED`。

## 定制或商务联系 
QQ：467643531
