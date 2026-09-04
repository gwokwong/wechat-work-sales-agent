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

## M1：真实企业微信接入

> 状态：**核心代码已完成并通过本地单元测试**（`mvn test` 全绿，仅自造样例验证加解密算法，**尚未与真实企微账号联调**）。
> 剩余工作为「老板提供真实企业物料 → 填 `application-wecom.yml` → 真实联调」，见下文清单。

### 1. 本阶段已完成（代码实现）

| 模块 | 实现 | 位置 |
|---|---|---|
| 官方 API 客户端 | access_token 获取与过期前 5 分钟自动续期缓存；`getchatdata` 增量拉取（seq、limit=1000、next_seq）；`message/send` 应用消息发送（返回真实 msgid，错误码 60011/45009/48002 等降级说明） | `channel/WeComApiClient.java` |
| 会话存档解密 | RSA(PKCS1/PKCS8 PEM 自动识别) 私钥解 `encrypt_random_key` → AES-256-CBC(PKCS7, IV=key 前 16 字节) 解密 `encrypt_chat_msg` | `crypto/ArchiveDecryptor.java`、`crypto/AesCbc.java` |
| seq 游标持久化 | `archive_seq` 单行表（`seq_cursor` 列），拉取后按回包最大 `next_seq` 更新 | `domain/ArchiveSeqState.java` + `schema.sql` |
| 存档消息映射 | 明文 JSON → 领域 `Message`：文本类可定位外部客户（单聊/群聊方向启发式）才投递，员工外发/非文本跳过 | `channel/ArchiveMessageMapper.java` |
| 定时增量拉取 | `@Scheduled`（默认 10s 首拉后每 60s），`app.wecom.enabled=true` 才注册，无凭据自动跳过不刷屏 | `channel/WeComArchivePuller.java` |
| 回调加解密 | 语义对齐官方 `WXBizMsgCrypt`：SHA1 验签、EncodingAESKey(43)→32 字节 key、AES-256-CBC(PKCS7)、原文=随机16B+网络序 len+msg+receiveId | `crypto/WXBizMsgCrypt.java` |
| 回调 Controller | `GET /wecom/callback` URL 验证回显；`POST /wecom/callback` 验签失败 401、成功立即 ack `success`、异步投 MessageBus（msgId 幂等） | `web/WeComCallbackController.java`、`channel/WeComChannel.java`、`channel/WeComCallbackXml.java` |
| 配置接线 | `AppProperties.Wecom` 扩展 + `application-wecom.yml`（默认不激活 profile）；`application-demo.yml` M0 mock 链路不受影响 | `config/AppProperties.java`、`application-wecom.yml` |
| 单元测试 | 加解密往返/验签/篡改防护/密钥格式/RSA 全链路/存档映射方向/XML 解析 + M0/M1 核心逻辑，共 40 例全绿（18 例加解密与通道 + 22 例状态机/策略/报价/上下文/REST） | `src/test/java/...` |

本地验证范围：仅用**自造样例**做算法往返与方向判断验证；不构成「已与真实企微联调」声明。

### 2. 剩余真实接入步骤（需老板在企微管理后台准备物料）

| 物料 | 说明 | 配置项 |
|---|---|---|
| corpId | 「我的企业 → 企业信息」 | `app.wecom.corp-id` |
| 会话存档 secret | 「安全与管理 → 管理工具 → 会话内容存档」开通并获取；需配可信 IP | `app.wecom.session-secret` |
| 会话存档 RSA 私钥 | 后台生成公钥上传企微，私钥下载保存（PKCS1 PEM） | `app.wecom.session-archive-private-key`（建议环境变量注入） |
| 回调 Token / EncodingAESKey | 自建应用「接收消息服务器配置」，配公网 URL `https://你的域名/wecom/callback` | `app.wecom.callback-token` / `callback-aes-key` |
| agentId / app-secret | 自建应用（需「客户联系」权限与员工发送客户消息授权） | `app.wecom.agent-id` / `app-secret` |
| 可信 IP | 服务器出口 IP 加入企微「企业可信 IP」 | — |

### 3. 启动真实模式

```bash
# 1) 复制/编辑 src/main/resources/application-wecom.yml 填入真实物料（或环境变量注入）
# 2) 先按 schema.sql 在 MySQL 建表（新增 archive_seq 表）
# 3) 以 wecom + mysql profile 启动
mvn spring-boot:run -Dspring-boot.run.profiles=mysql,wecom
```

- `app.wecom.enabled: true` 后，WeComChannel / 回调 Controller / 存档定时拉取才注册；未填真实凭据时启动不报错，回调返回失败提示、拉取自动跳过。
- `app.channel.active: wecom` 后外发走 `message/send`（`external-to-userid` 映射解析 touser；映射缺失按原 external_userid 直发并 WARN）。
- 保持 `app.approval-mode: MANUAL`：真实发送前由销售人工审批把关。

### 4. 真实联调注意事项

- 外发合规：企业微信对主动外发客户消息有窗口/通道限制（如 48 小时会话窗口、需客户联系权限），联调前确认后台授权，避免 60011/48002。
- 频率限制（令牌桶）仍是 TODO 接入点（`DESIGN.md §7.4`），未上线生产前建议人工审批兜底。
- 语音/图片等非文本消息 M1 仅落日志跳过，转文本需另接语音识别/图片理解（TODO）。
- 私钥/secret 严禁入库入 git，生产走 KMS/环境变量。

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
    │   ├── action/QuoteService.java, MockQuoteService.java, HttpQuoteService.java,
    │   │          QuoteResult.java, QuoteRequest.java, ActionLogger.java
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
