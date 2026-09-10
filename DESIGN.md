---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 4f152940444a68ea1ec4ce3748abc308_009d864eacc111f18874525400287e28
    ReservedCode1: 7ifYY3MM5EOUO0vE8ZnCadPw0Z9mD7i17ZtfY7xUnQBupF1p+3FUbasiK9qcP237S12kAvj1iQCkuTgDblbA8nLxC57vCr/ZnHvsDqUCB2O7xPb0ATKUjKvd112isAeJa8ahuZQKF9GL8hCSdqSX9Jo+pF/KjgNTYme0emrREpmQ2jZeMFKxSeBhVB4=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 4f152940444a68ea1ec4ce3748abc308_009d864eacc111f18874525400287e28
    ReservedCode2: 7ifYY3MM5EOUO0vE8ZnCadPw0Z9mD7i17ZtfY7xUnQBupF1p+3FUbasiK9qcP237S12kAvj1iQCkuTgDblbA8nLxC57vCr/ZnHvsDqUCB2O7xPb0ATKUjKvd112isAeJa8ahuZQKF9GL8hCSdqSX9Jo+pF/KjgNTYme0emrREpmQ2jZeMFKxSeBhVB4=
---

# DESIGN — 企业微信销售 Agent 设计文档

> 版本：v0.2（M0 骨架演示版 → M1 企微接入代码完成 → 截图工作流落地）　状态：设计定稿 + 骨架实现可运行（解密/回调/拉取/系统管理/前端 sale-ui/截图工作流均已落地，真实企微物料联调待外部账号，见 §11 与 README）
> 目标读者：本仓库开发同学、企业微信接入与安全评审同学、业务系统对接同学

---

## 1. 需求拆解与边界

### 1.1 全局目标

基于**企业微信官方会话内容存档**能力构建"销售 Agent"：
读取企微客户聊天、维护客户上下文画像、识别销售阶段、按回复策略自动生成并发送消息；报价等动作对接已有业务系统。

### 1.2 核心能力清单（MoSCoW）

| 编号 | 能力 | 优先级 | M0 骨架落地 |
|---|---|---|---|
| C1 | 接收企微客户消息（会话存档） | Must | `Channel` + `MockChannel`（每分钟注入）|
| C2 | msgId 幂等去重 | Must | `message_log.msg_id` 唯一 + `existsByMsgId` |
| C3 | 客户建档 / 联系人画像 | Must | `contact` / `customer_profile` |
| C4 | 短期上下文（最近 N 轮） | Must | `context_recent_size` 轮内联取 |
| C5 | 销售阶段识别与状态机 | Must | `RuleStageClassifier` + `StageMachine` |
| C6 | 按阶段+触发条件生成话术 | Must | `strategy_config` + `strategies/default.json` |
| C7 | 合规校验（敏感词/红线） | Must | `ComplianceFilter` |
| C8 | 人工闸门（审批后发送） | Must | `ReplyDraft PENDING→APPROVE→SENT` |
| C9 | 外发消息（企微 API） | Must | `OutboundSender`（M0=Mock 通道直写）|
| C10 | 报价对接业务系统 | Should | `QuoteService` SPI + `MockQuoteService` |
| C11 | LLM 生成润色 | Should | `LLMClient` + `MockLLMClient` |
| C12 | 审计日志 | Should | `action_log` + REST `/api/logs` |
| C13 | 真实会话存档解密 | Won't(M1) | 已实现：M1 解密链路完成（见 §7.1），仅剩真实物料联调 |

### 1.3 设计边界

- **不伪造企微消息**：M1 数据来源只能是官方会话存档 API/回调；Mock 仅用于演示链路。
- **不自动外发未审内容**：默认人工审批；AUTO 模式仅用于自动化测试。
- **不承载违规敏感内容**：回复策略与话术模板全部本地可控，敏感词本地过滤，不依赖 LLM 自觉。
- **不用通用 executor 代替官方能力**：真实会话存档必须走官方 SDK/API（`WeComApiClient` 内注释给出官方限制）。

---

## 2. 架构分层

```
┌──────────── 表现层 REST / Admin ────────────┐
│ AdminController（/api）: 客户/阶段/草稿/发送/日志/策略/演示 │
└───────────────▲──────────────────────────────┘
                │ HTTP
┌───────────────┴─────────── 应用服务层 ───────────────────────┐
│ SalesAgentOrchestrator（MessageListener，串起全链路）          │
│  收消息→幂等→建档→落库→上下文→分类→状态机→画像→策略→LLM→合规→草稿│
│ StageMachine / DealService（商机与阶段推进）                  │
│ StrategyService / DraftApprovalService（策略/模板/审批）      │
│ ContextAssemblyService / CustomerProfileService（上下文画像） │
└──────┬──────────────┬───────────────────┬───────────────────┘
       │              │                   │
┌──────▼─────┐ ┌──────▼──────┐ ┌─────────▼─────────┐
│ 通道层      │ │ 外部能力 SPI │ │ 基础设施 / 数据层   │
│ Channel     │ │ LLMClient   │ │ Spring Data JPA   │
│ MessageBus  │ │ QuoteService│ │ H2 / MySQL(schema)│
│ Mock/WeCom  │ │ Compliance  │ │ action_log 审计    │
└─────────────┘ └─────────────┘ └───────────────────┘
```

**分层原则**：
1. 通道层只负责"收发"，不处理业务：回调/拉取侧快速落 `MessageBus`。
2. 编排器（orchestrator）单一职责串联，业务实体变动不触达通道实现。
3. 外部系统（LLM / 报价 / 企微）一律走接口（SPI），切换实现只改配置与 Bean。
4. 所有跨系统副作用留痕：`action_log` 统一审计。

---

## 3. 销售阶段状态机

### 3.1 状态定义

| 枚举 | 含义 | 进入信号（规则分类示例） |
|---|---|---|
| `LEAD_INITIAL` | 新线索，未确认意向 | 无任何意向关键词（默认）|
| `QUALIFIED` | 线索合格（有预算/决策权/时间信号）| "有预算""我们负责采购""计划 Q3 上" |
| `NEEDS_ANALYSIS` | 需求分析中 | "需要""想了解""有什么功能""怎么对接" |
| `PROPOSAL` | 已给方案/报价沟通 | "什么价位""多少钱""报个价""预算多少" |
| `NEGOTIATION` | 商务谈判 | "价格还能再谈吗""优惠""折扣""合同条款" |
| `WON` | 成交 | "我们定了""就选你们""签合同" |
| `LOST` | 丢失 | "不考虑了""选了别家""太贵了算了" |

### 3.2 跃迁矩阵（forward-only 推进 + 有限回退）

| 当前＼目标 | LEAD_INITIAL | QUALIFIED | NEEDS_ANALYSIS | PROPOSAL | NEGOTIATION | WON | LOST |
|---|---|---|---|---|---|---|---|
| LEAD_INITIAL | ✅ | ✅ | ✅ | ✅ | — | — | — |
| QUALIFIED | — | ✅ | ✅ | ✅ | — | — | — |
| NEEDS_ANALYSIS | — | — | ✅ | ✅ | — | — | — |
| PROPOSAL | — | — | — | ✅ | ✅ | ✅ | ✅ |
| NEGOTIATION | — | — | — | — | ✅ | ✅ | ✅ |
| WON / LOST | — | — | — | — | — | ✅终态 | — |

- 正向：**不允许回退**（`LEAD_INITIAL→QUALIFIED→NEEDS_ANALYSIS→PROPOSAL→NEGOTIATION→WON/LOST` 单向推进）。
- 终态：`WON` / `LOST` 不再跃迁（幂等）。
- 分类器建议可能因信号滞后回退时，`DealService.applyClassifiedStage` 只取**不小于当前**的阶段生效（等价于状态机的"跃迁矩阵优先 + 抑制回退"），抑制回退产生 `action_log` 提示，避免阶段反复震荡。
- 手动 REST `/api/deals/{id}/transition` 仍按上述矩阵校验，非法跃迁抛 `StageTransitionException`。

### 3.3 阶段推进副产物

- 阶段变化 ⇒ 写 `deal`（`stage` 历史由 `action_log` 留痕）+ 更新画像 `stage_summary`（如"客户处于方案报价阶段，关注价格"）。
- 阶段变化 ⇒ 参与 `StrategyService.selectForMessage` 的策略选择（不同阶段启用不同话术集）。

---

## 4. 回复策略模型

### 4.1 配置模型（`strategy_config` / `strategies/default.json`）

策略 = **阶段 × 触发条件 × 动作**：

```json
{
  "ruleId": "proposal-price-ask",
  "ruleName": "报价询问-报价单",
  "stage": "PROPOSAL",
  "priority": 10,
  "triggerKeywords": ["什么价位", "多少钱", "报个价", "费用多少", "预算多少"],
  "actionType": "CREATE_QUOTE",
  "template": "您好，根据您的需求已整理方案报价【报价单 ${quoteReference}】，请查收～如需调整可以随时告诉我。",
  "enabled": true
}
```

| 字段 | 说明 |
|---|---|
| `stage` | 匹配的销售阶段 |
| `triggerKeywords` | 客户消息命中任一关键词即触发（大小写不敏感）|
| `priority` | 同阶段多条策略按 priority 降序匹配；都未命中则走 `*-fallback` 兜底策略 |
| `actionType` | `SEND_TEXT` 纯话术 / `CREATE_QUOTE` 先调报价 SPI 再发话术 / `HUMAN_TRANSFER` 转人工（已实现：命中即写审计 `HUMAN_TRANSFER_REQUESTED`，不生成 AI 话术、不受防骚扰间隔限制，等待人工介入）|
| `template` | 话术模板，支持 `${quoteReference}` `${contactName}` 占位渲染 |
| `enabled` | 是否启用 |

### 4.2 回复流水线（重要：人审兜底，AI 不直接外发）

```
客户消息 → 命中策略 → 模板渲染 → LLM 润色（可关，保留原文兜底）
   → ComplianceFilter 合规校验
        ├─ 通过 → ReplyDraft(PENDING) ──人工审批──▶ approve → OutboundSender → SENT
        ├─ 命中软规则（如超长）→ 自动截断后仍 PENDING
        └─ 命中硬规则（敏感词/私密承诺/营销红线）→ BLOCKED（须人工改写，不走自动发送）
AUTO 模式（app.approval-mode=AUTO，仅测试）→ 自动 approve + 发送
```

### 4.3 LLM 定位

- LLM 只做**润色/扩写**，不自由发挥事实性承诺：`MockLLMClient` 默认原样返回模板（保证演示可预期）；真实接入时由 `LLMClient` 实现加上"仅润色、不得新增承诺/价格"的 system 约束。
- 输入上下文用 `ContextAssemblyService.renderSummary`（画像摘要 + 阶段 + 最近 N 轮 + 触发话术），输出超长会被 Compliance 拦截/截断。

---

## 5. 上下文管理方案

### 5.1 三级上下文

| 层级 | 存储 | 内容 | 生命周期 |
|---|---|---|---|
| 短期 | `message_log`（最近 `context_recent_size` 轮内联）| 原始消息（客户/我方），用于分类与润色 | 随消息自然滚动 |
| 中期 | `deal` | 当前销售阶段、阶段变更时间 | 商机周期 |
| 长期 | `customer_profile` | 客户档案：需求摘要（最近 5 条客户消息提取式滚动）、预算范围、时间窗、公司信息、画像备注 | 客户全生命周期 |

### 5.2 组装流程（`ContextAssemblyService.assemble`）

1. 按 `contactId` 取 `Contact`；
2. 取最近 `context_recent_size` 条双向消息（客户消息与已发送消息），按时间正序；
3. 取 `Deal`（无则默认 `LEAD_INITIAL`）与 `CustomerProfile`（无则初始化空画像）；
4. 包成 `ContactContext`（含 `recentCustomerMessages`），供分类器/策略/LLM 使用。

### 5.3 画像沉淀（`CustomerProfileService.updateAfterMessage`）

- 阶段跃迁：更新 `stage_summary`（如"处于方案报价沟通，对价格敏感"）。
- 每次入站消息：抽取客户消息滚入 `needs_summary`（保留最近 5 条客户消息原文，截断单条 200 字符）；
- 规则提取：`ProfileRuleExtractor` 用轻量正则启发式从消息文本提取预算范围/时间窗——预算支持区间（30-50万/3到5万）与单值（预算 5 万/约为 30 万，带"预算/费用/报价"等语境词防误报）；时间窗支持季度(Q1-Q4)、月份(2026年11月底)、相对(年底/下个月/明年)、促销节点(618/双十一/双12)；命中即覆盖沉淀到 `budget_range`/`time_window`，无法可靠提取时保留空并记录原因（log），后续可替换为 LLM 提取增强。
- 画像由服务层更新，不直接暴露写接口给通道层。

---

## 6. 数据表设计（MySQL `schema.sql`；H2 demo 由 JPA 自动建表）

| 表 | 关键字段 | 说明 |
|---|---|---|
| `contact` | id, external_userid(唯一), name, avatar_url, corp_name, tags, remark, created_at, updated_at | 企微客户（external_userid 对应企微 external_userid）|
| `customer_profile` | id, contact_id(唯一FK), company, role, needs_summary, budget_range, time_window, stage_summary, remark, created_at, updated_at | 客户画像/长期摘要 |
| `message_log` | id, msg_id(唯一索引), contact_id(FK), direction(IN/OUT), sender_type, content, msg_type, channel_type, processed, created_at | 全部聊天流水，msg_id 幂等 |
| `deal` | id, contact_id(唯一FK), stage, amount, closed_at, created_at, updated_at | 商机/阶段状态机 |
| `reply_draft` | id, contact_id(FK), source_msg_id, stage, strategy_name, action_type, content, reason, status(PENDING/APPROVED/SENT/REJECTED/BLOCKED), blocked_reason, quote_reference, decided_at, sent_at, created_at | AI 候选话术 + 人工闸门 |
| `strategy_config` | id, rule_id(唯一), rule_name, stage, priority, trigger_keywords, action_type, template, enabled | 回复策略（可由 JSON 装载）|
| `quote_request` | id, contact_id, status, quote_no, amount, created_at | 报价 SPI 请求留痕（M0 Mock 实现写入）|
| `action_log` | id, contact_id(可空), action_type, detail, created_at | 全局审计（阶段/草稿/发送/报价等）|

外键约束与索引见 `schema.sql`；`msg_id` 唯一索引是幂等第一道防线。

---

## 7. 企微接入要点（M1）

> 本阶段状态：接入层代码已完成并通过本地单测（自造样例验证），尚未与真实企微账号联调。

### 7.1 会话存档消息获取与解密（已实现）

- 官方接口：`gettoken`（会话存档 secret）→ `getchatdata`（seq 增量拉取，limit≤1000，回包 `next_seq` 为下次起点）。
- 解密链路（与官方语义核对一致，见 `crypto/` 实现）：
  `encrypt_random_key`（Base64）→ RSA 私钥（PKCS#1 v1.5，2048bit）解密 → 32 字节 `randomKey`
  → 以 randomKey 为密钥、前 16 字节为 IV 对 `encrypt_chat_msg` 做 **AES-256-CBC + PKCS7** 解密 → 消息明文 JSON。
  （M0 文档此处曾误记为 AES-256-GCM，M1 编码前已联网核对官方文档更正为 CBC/PKCS7。）
- seq 游标持久化：`archive_seq` 表（单行 id=1，`seq_cursor` 列避免 MySQL 保留字），拉取成功后再推进。
- 明文映射：`ArchiveMessageMapper` 仅将「文本类且可定位外部客户」映射为 IN `Message`；员工外发、
  群聊内部成员发言走 `RoomMemberResolver` 归属群内唯一外部客户；语音/企微消息内图片等非文本跳过
  （转文本依赖外部语音识别/ASR 与会话存档媒体下载，见 §11 外部依赖项）。用户主动上传的「聊天截图」场景
  已通过截图工作流本机 OCR 落地（见 §10），不依赖企微媒体下载。
- 实现类：`crypto/ArchiveDecryptor.java`、`crypto/AesCbc.java`、`channel/WeComApiClient.java`、
  `channel/WeComArchivePuller.java`、`channel/ArchiveMessageMapper.java`、`domain/ArchiveSeqState.java`。

### 7.2 msgId 幂等去重（已实现）

- `message_log.msg_id` 唯一索引（DB 层） + `existsByMsgId` 前置判断（应用层双保险）。
- 幂等覆盖范围：同一 msgId 重复回调/重复拉取不会重复建档、重复生成草稿、重复发送。
- 存档拉取侧（`WeComArchivePuller`）在 publish 前同样做 `existsByMsgId` 去重；回调侧（`WeComChannel`）
  异步任务内先幂等判断再投总线。

### 7.3 回调 5 秒 ack + 异步处理（已实现）

- `WeComCallbackController`：GET URL 验证（echostr 解密回显）；POST 验签解密后**立即返回** `success`；
  验签失败返回 401。
- 消息处理由 `wecomCallbackExecutor`（2-8 线程 + 2000 队列）异步执行：幂等判断 → `MessageBus.publish`；
  任务被拒绝不阻塞 ack（丢消息由会话存档拉取兜底补齐）。
- 加解密语义（`crypto/WXBizMsgCrypt.java`）：EncodingAESKey 43 位 + "=" Base64 → 32 字节 key；
  AES-256-CBC（IV=key 前 16 字节，PKCS7）；原文 = 随机16字节 + 4 字节网络序 msgLen + msg + receiveId(corpId)；
  签名 = SHA1(字典序拼接 token/timestamp/nonce/encrypt) 小写 hex。
- 注意回调重试导致的重复投递由 7.2 幂等收敛。

### 7.4 频率限制

- 通道侧对每个 external_userid 做令牌桶（`wecom.rate-limits`），防止营销话术短时间轰炸 —— 已实现：客户级令牌桶 `RateLimitService` + `OutboundSender` 接入。
- 编排器发送前二次校验已实现：`violatesMinInterval` 查最近一次 OUT 外发时间与当前间隔，不足策略级 `minIntervalMinutes` 则静默跳过并写审计 `STRATEGY_INTERVAL_SKIPPED`（策略 JSON/DB 均可配该字段）。

### 7.5 安全红线（真实企微）

- 私钥/secret 走环境变量或 KMS，绝不入库入 git（`application-wecom.yml` 已注释提醒）。
- 外发消息必须满足企微外部联系人消息规则（48 小时主动消息窗口、客户联系/应用消息通道差异），
  `WeComApiClient` 对 60011 无权限 / 45009 频率限制 / 48002 api 不可用做日志与降级说明。

---

## 8. 与已有业务系统对接（SPI）

### 8.1 QuoteService（报价）

```java
public interface QuoteService {
    QuoteResult createQuote(QuoteRequest req); // 对接业务系统报价单/CRM
}
```
- 编排器在 `actionType=CREATE_QUOTE` 时调用；`MockQuoteService` 返回 `Q-<ts>` 模拟报价号与金额；
- 真实实现：内部 HTTP 调用/消息队列发布报价单请求 → 轮询/异步回调拿 `quoteNo` → 写入 `quote_request` 与 `reply_draft.quote_reference`。

### 8.2 更多对接点（扩展路径）

- 审批流对接：`DraftApprovalService` REST 化已是天然接口，可接企业 OA 审批回调。
- 客户阶段同步 CRM：监听 `action_log`（`STAGE_TRANSITION`）或扩展 `DealListener`。
- 策略配置中心：`strategy_config` 表已可热更新；如需远端配置可替换 `StrategyService` 装载源。
- 画像数据增强：`CustomerProfileService` 预留扩展点（对接 BI/标签系统）。

---

## 9. 配置与运行

| 配置 | 默认（demo）| 说明 |
|---|---|---|
| `app.channel.active` | `mock` | `mock`/`wecom`；决定 OutboundSender 外发通道 |
| `app.wecom.enabled` | `false` | `true` 才注册 WeComChannel/回调 Controller/存档拉取；真实接入用 `application-wecom.yml` profile（`mysql,wecom`） |
| `app.approval-mode` | `MANUAL` | `MANUAL` 人工审批 / `AUTO` 自动发送（测试）|
| `app.llm.mock` | `true` | 是否启用 MockLLM（true 时无需真实模型）|
| `app.mock.inject-enabled` | `true` | MockChannel 定时注入开关 |
| `app.mock.script-path` | 内嵌剧本 | 演示剧本（`M0 演示跑法`）|
| `context.recent-size` | 10 | 短期上下文最近消息轮数 |

数据源：demo profile 走 H2 内存（`ddl-auto=update`，JPA 自动建表）；mysql profile 走 MySQL（`ddl-auto=none`，执行 `schema.sql`）。

---

## 10. 截图工作流

> 场景：销售手头只有聊天截图，想快速得到对应话术。该工作流独立于企微会话存档导入，用户在前端 `/sales/screenshot` 手动上传截图即可。

### 10.1 链路

```
上传截图 → 逐张 OCR 识别 → 前端编辑 / 合并文本并选客户 → 提交 process → 沿用编排生成话术草稿 → 一键复制 / 导出（.txt / .docx）
```

- 接口：`POST /api/screenshot/upload`（多文件，`file` 字段，最多 `app.screenshot.max-batch-count` 张）、`POST /api/screenshot/process`、`GET /api/screenshot/drafts/{id}/export?format=txt|docx`。
- 落盘：上传图片 → `app.screenshot.upload-dir`，导出文件 → `app.screenshot.export-dir`；均经静态映射 `/uploads/**` 提供下载 URL。
- 模块：`screenshot/ScreenshotService`（存储 / 批量识别 / 入编排 / 导出）、`ScreenshotOcrService`（OCR 引擎路由）、`OcrMode` / `OcrResult`（模式与结果模型）、`rest/ScreenshotController`。

### 10.2 编排复用（沿用 `SalesAgentOrchestrator.onMessage`）

- process 不是另写一套回复逻辑，而是把确认后的聊天文本组装成一条入站消息，直接走 `SalesAgentOrchestrator.onMessage` 既有链路：幂等 → 建档 → 画像 → 阶段分类 → 策略 → 合规 → 生成 `reply_draft`，保证截图场景与企微消息场景的回复口径完全一致。

### 10.3 OCR 引擎探测与模式降级

- 模式：`auto`（默认）/ `external` / `off`（`app.ocr.mode`）。
- `auto` 路由顺序：本机 tesseract CLI → 外部 OCR 服务（`app.ocr.external-base-url` + POST `/ocr`，可选 `external-api-key`）→ PLACEHOLDER 占位文本（前端手动粘贴 / 编辑）。
- **探测与识别解耦（关键设计）**：引擎可用性由 `commandAvailable`（ProcessBuilder 运行 `tesseract --version`）独立判定并缓存，与单次图片识别结果无关。只要引擎可用，`recognizeTesseract` 一律返回 `mode=TESSERACT` 的结果——**即使某张图识别文本为空也返回 `text=""`，绝不因空结果误判引擎不可用而降级到占位**。仅当命令不可用 / 持续异常时才返回 PLACEHOLDER。线上曾踩坑：空识别与引擎探测耦合导致真实 OCR 被误关，本设计已解耦根治。
- tesseract 用绝对路径配置：`app.ocr.tesseract-path=C:/Program Files/Tesseract-OCR/tesseract.exe`、`tesseract-lang=chi_sim+eng`；本机 Tesseract 5.4（含 chi_sim 简体中文包）验证可用。

### 10.4 空文本不入链护栏

- 空文本 / 纯空白识别结果**不会进入 AI 编排链路**：后端 `storeAndRecognizeAll` 对每条识别文本做 trim 判断并给出"空文本"标记；前端对空结果按 `ocr.mode` 分场景提示：
  - `TESSERACT` + 空 → "图片中未识别到文字，可手动输入或换图"；
  - `PLACEHOLDER` → "OCR 未就绪，已返回空识别结果，可手动输入"。
- 未确认 / 空文本的条目不参与合并生成，防止把空文本喂给编排器产生无意义的回复。

### 10.5 多图批量与导出

- 多图：`storeAndRecognizeAll(List<MultipartFile>)` 逐张落盘 + 识别为结果列表返回；前端逐张展示、可选择性合并（多张文本按换行拼接为一段聊天记录）。
- 导出：`exportDraft` 支持 `txt`（UTF-8 纯文本）/ `docx`（用 JDK 自带 `ZipOutputStream` 手写最小合法 WordprocessingML——`[Content_Types].xml + _rels/.rels + word/document.xml`，中文内容直接入 XML，零第三方依赖，兼容 Word / WPS 打开）。
  - 选型说明：相比引入 Apache POI（重依赖、与项目现状不符）与 HTML→doc（Word 兼容性差、易被识别成网页），手写合法 OOXML 是最稳妥、可控且无新增依赖的方案；`buildDocx` 内置对 XML 特殊字符与非法控制字符的转义，避免生成损坏文件。

---

## 11. 可观测性与扩展 TODO（M1+）

- [x] 截图工作流（上传 → OCR → 编辑合并 → 复用编排生成话术 → 复制 / 导出 txt/docx）— 已实现：见 §10；本机 Tesseract 5.4 含 chi_sim
- [x] 企微会话存档解密实现与本地单测夹具（ArchiveDecryptor + AesCbc，RSA+AES 全链路）
- [x] 回调控制器 + 验签 + 5s ack + 异步消息推送（WeComCallbackController + WeComChannel）
- [x] getchatdata 增量拉取 + seq 游标持久化 + 定时任务（WeComArchivePuller + archive_seq）
- [ ] 真实企微物料联调（等老板提供 corpId/secret/私钥/agentId 等，见 README M1 清单）
- [x] 通道级/客户级频率限制（令牌桶）— 已实现：客户级令牌桶 RateLimitService + OutboundSender 接入，`app.wecom.rate-limit-enabled/capacity/per-second` 可配（默认关闭不影响演示）
- [~] 语音转文本 / 企微消息内图片转文本 — 部分落地：聊天截图图片 OCR 已通过截图工作流本机 tesseract 落地（见 §10）；会话存档内语音 / 企微媒体图片转文本仍依赖外部语音识别/ASR 与企微媒体素材下载（access_token + 媒体 API + 存储）
- [~] 群聊「群成员 → 外部客户」完整映射 — 部分实现：RoomMemberResolver 静态映射(room-external-members) + 实时 externalcontact/groupchat/get 解析（带缓存，失败回落静态）；完整实时联调依赖企微「客户联系」权限与真实账号物料
- [~] 真实 LLMClient 实现（系统提示词约束：只润色不新承诺）— 代码已实现 HttpLLMClient（OpenAI 兼容 chat/completions，含系统提示词约束）；真实生效需 `app.llm.mock=false` + base-url/api-key/model（外部模型账号/密钥）
- [x] ComplianceFilter 规则外置（词库/正则配置化）— 已实现：敏感词 `app.compliance.sensitive-words`、阻断正则 `blocked-patterns`、规则名 `pattern-rule-names`（application.yml 可配，无需改代码）
- [x] M0/M1 核心逻辑单元测试补强（stage/strategy/action/context/rest/web，JUnit5+Mockito，22 例新增）
- [x] 真实报价 HTTP 适配器（HttpQuoteService，条件装配 + bizRefNo 回填 + FAILED 兜底，MockRestServiceServer 单测）
- [x] 业务系统报价/CRM SPI 重试/幂等增强（幂等键/失败重投/回调轮询确认）— 已实现 HttpQuoteService 幂等键（Idempotency-Key 头）+ maxAttempts 指数退避重试 + FAILED 兜底；「回调轮询确认」未实现，归真实 SPI 联调评估
- [~] 消息分片/长文本、图片/文件消息处理 — 长文本分片已实现（OutboundSender 按换行边界拆分 + 编号前缀 + `max-outbound-length`）；图片/文件消息处理依赖企微媒体下载 API/存储/OCR，不可独立实施


## 定制或商务联系
QQ：467643531
*（内容由AI生成，仅供参考）*
