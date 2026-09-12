# 企业微信销售 Agent（WeChat Sales Agent）

> **这个项目帮你把企微销售流程自动化，让 AI 替你跟进客户。**

基于**企业微信官方会话存档方案**的单体 Spring Boot 项目。它把"看聊天 → 记客户 → 想话术 → 发消息"这条销售链路交给 AI 自动化：读取企微客户聊天（会话存档）→ 维护客户上下文画像 → 识别销售阶段 → 按回复策略自动生成消息 → 人工闸门确认 → 外发，报价等动作对接已有业务系统（SPI）。销售只需要在关键节点点一下"确认发送"，其余重复劳动交给 Agent。

- 技术栈：Java 17 + Spring Boot 3.2.5 + Maven + Spring Data JPA
- 持久化：默认 H2 内存库（演示零依赖）；生产可切 MySQL（提供 `schema.sql`）
- LLM：`LLMClient` 抽象接口 + `MockLLMClient`（演示无需真实模型 Key）
- 企微通道：`Channel` 抽象 + `MockChannel`（每分钟注入一条模拟客户消息）+ `WeComChannel`（真实接入代码已实现：回调加解密 / 会话存档拉取解密 / 外发，待供应商提供真实物料联调）

## 面向销售 / 业务人员

不关心代码？你只需要知道：**这个 Agent 是你在企微里的"数字销售助理"**，帮你把和客户在企微里的每一条聊天都变成有价值的跟进动作。

| AI 替你做什么 | 白话解释 |
|---|---|
| 自动跟进客户 | 客户在企微说了话，Agent 自动接住、回复，你不用一直盯着企微 |
| 客户画像洞察 | 聊天内容自动沉淀成"这位客户关心什么、预算多少、什么时候要买"，不用自己翻记录 |
| 话术润色 | AI 根据当前阶段自动起草回复话术，你确认后一键发送 |
| 合规防违规 | 敏感词 / 夸大承诺 / 索要银行卡账号等话术自动被拦下，避免踩监管红线 |
| 转人工兜底 | 客户明确要真人对接时，Agent 自动转给销售人工处理，不硬扛 |
| 报价对接 | 命中商机场景时自动调用内部报价系统，报价号直接进话术 |

**典型场景一（自动跟进）**：客户发来"你们报价方案我看过了，价格还能谈吗？"，Agent 自动识别出客户处于「商务谈判」阶段，生成一条得体的议价回应草稿，销售打开待审批列表点确认即发送，无需自己打字。

**典型场景二（画像沉淀）**：客户在聊天里提到"我们 6 月有采购预算，优先选能对接我们 ERP 的"，这些信息被 Agent 自动提取进客户画像。下次跟进时销售一眼看到"预算/时间窗/需求点"，开场就能切中要害。

> 安全兜底：所有 AI 生成的话术默认走**人工审批**（MANUAL 模式）——任何内容必须销售确认后才外发，AI 不擅自替你"发话"。

## 功能特性清单

| 状态 | 功能 | 说明 |
|---|---|---|
| ✅ 已实现 | 企微会话存档拉取与解密 | `getchatdata` 增量拉取 + RSA/AES 解密，本地单测已验证 |
| ✅ 已实现 | 回调验签与加解密 | `/wecom/callback` 验签、5 秒内 ack，消息幂等去重投递 |
| ✅ 已实现 | 客户级频率限制 | 令牌桶限流，防同一客户被消息轰炸（默认关闭，可配置） |
| ✅ 已实现 | 合规过滤 | 敏感词 + 正则规则外置可配，命中自动 BLOCKED 不发送 |
| ✅ 已实现 | 报价对接 SPI | `QuoteService` 接口 + Mock 实现，HTTP 实现带幂等重试 |
| ✅ 已实现 | 长文本分片 | 超长外发消息按换行边界拆分多条，规避企微长度限制 |
| ✅ 已实现 | 转人工 | HUMAN_TRANSFER 动作策略，客户要求真人时自动转人工 |
| ✅ 已实现 | 客户画像提取 | 预算 / 时间窗 / 需求点从聊天自动提取（ProfileRuleExtractor） |
| ✅ 已实现 | 系统管理域 | 用户 / 角色 / 菜单 / 权限（RBAC），含前端页面 |
| ✅ 已实现 | 前端控制台 | sale-ui：客户 / 商机 / 草稿审批 / 日志 / 策略 / 系统管理全开通 |
| ⚙️ 可配置 | 演示 ↔ 真实通道切换 | `app.channel.active`：mock 演示 / wecom 真实 |
| ⚙️ 可配置 | LLM 平滑切换 | mock 规则生成 ↔ OpenAI 兼容 HTTP 模型（DeepSeek 等） |
| ⚙️ 可配置 | 审批模式 | MANUAL 人工审批（默认）/ AUTO 自动发送 |
| 🔗 依赖外部物料 | 真实企微联调 | 需 corpId / 会话存档 secret / RSA 私钥 / 回调 Token / agentId 等 |
| 🔗 依赖外部物料 | 真实 LLM | 需模型 API Key（HttpLLMClient 已就绪） |
| 🔗 依赖外部物料 | 语音 / 图片消息转文本 | 需另接语音识别 / 图片理解（当前跳过仅落日志） |
| 🔗 依赖外部物料 | 群成员实时映射 | 群聊 external_userid ↔ 客户需真实企微物料联调 |

## 项目状态（完成度说明）

- **M0 零依赖演示链路：可用**。克隆后 `mvn spring-boot:run` 即可看到完整"收消息 → 建档 → 画像 → 阶段推进 → 话术生成 → 人工审批"闭环，无需任何外部账号与 Key。
- **管理端与前端：可用**。登录 `admin / admin123` 即可体验客户 / 商机 / 草稿审批 / 系统管理等全部页面。
- **真实企微接入：代码已就绪，待联调**。加解密 / 拉取 / 回调 / 外发代码已完成并通过本地单测，只差老板提供真实企业物料接入联调。
- **截图工作流：已实现**。聊天截图上传 → OCR（本机 Tesseract 5.4 含 chi_sim）→ 编辑确认 → 复用编排生成话术 → 一键复制 / 导出（.txt / .docx）。
- **真实 LLM / 真实报价 / 语音 ASR：待集成**。抽象与 HTTP 实现已就绪，接入 API Key 或内部系统即可上线；语音转文本（ASR）尚未接入。

## 模块架构

```
                        ┌──────────────────────────────┐
                        │        REST 管理端            │
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
│ MessageBus  │      └───┤Store / │ │ 分类器  │ │Service    │ │ Filter   │
│ (幂等去重)    │          │Profile │ │+状态机  │ │+模板渲染   │ │(合规校验)  │
└─────────────┘          └───┬────┘ └───┬────┘ └─────┬─────┘ └──────────┘
                               │          │            │
┌───────────────┐              ▼          ▼            ▼
│  JPA / DB     │◀─── CustomerProfile / Deal / ReplyDraft / MessageLog / ActionLog
│ H2 / MySQL    │
└───────────────┘
                                ┌────────────────────┐
                                │ QuoteService (SPI) │ 报价对接业务系统（Mock 实现）
                                └────────────────────┘
```

## 面向开发者

### 技术架构总览

- **语言 / 框架**：Java 17 + Spring Boot 3.2.5，Maven 构建，Spring Data JPA 持久化
- **消息驱动**：`Channel` 抽象（收消息）→ `MessageBus`（幂等去重）→ `SalesAgentOrchestrator`（编排器）→ 策略 / 合规 / 审批 → 外发
- **关键特性**：通道可插拔（Mock / WeCom）、LLM 抽象（Mock / HTTP）、审批闸门（MANUAL / AUTO）、合规过滤外置、客户级限流、SSL/回调加解密、SPI 报价对接
- **持久化**：默认 H2 内存库（零依赖演示），生产切 MySQL（附 `schema.sql`）
- **统一响应**：`{ code, message, data }`，`code = 200` 表示成功（`ApiResponse`）

### 模块清单

| 模块 | 职责 | 关键类 |
|---|---|---|
| channel | 企微 / Mock 通道、消息总线、限流、回调解密、存档拉取 | `WeComChannel`、`MockChannel`、`MessageBus`、`WeComArchivePuller`、`RateLimitService`、`WeComApiClient` |
| crypto | 会话存档解密、企微回调加解密 | `ArchiveDecryptor`、`AesCbc`、`WXBizMsgCrypt` |
| context | 客户画像与上下文组装、画像规则提取 | `ContextStore`、`CustomerProfileService`、`ProfileRuleExtractor` |
| stage | 销售阶段分类与状态机 | `StageClassifier`、`StageMachine`、`DealService` |
| strategy | 回复策略、LLM 接入、合规过滤、草稿审批、编排器 | `StrategyService`、`LLMClient`、`ComplianceFilter`、`SalesAgentOrchestrator` |
| action | 外部动作 SPI（报价等）、动作日志 | `QuoteService`、`HttpQuoteService`、`ActionLogger` |
| service | 系统管理域（用户 / 角色 / 菜单权限） | `SystemAdminService` |
| rest / web | REST 管理接口、企微回调接口 | `AdminController`、`AuthController`、`SystemAdminController`、`WeComCallbackController` |

### 快速启动

**后端（默认零依赖演示）**

环境要求：JDK 17+、Maven 3.8+。

```bash
cd wechat-work-sales-agent
mvn spring-boot:run          # 默认 profile=demo：H2 + MockChannel + MockLLM + 人工审批
```

启动后访问 `http://localhost:8080`；MockChannel 每分钟注入一条模拟客户消息，自动跑通"收消息 → 建档 → 画像 → 阶段推进 → 话术生成 → 待审批"全链路。

**前端（sale-ui）**

环境要求：Node.js ≥ 20.19、pnpm ≥ 8.8。

```bash
cd sale-ui
pnpm install
pnpm dev                    # 默认端口 3006，vite 已将 /api 代理到 http://localhost:8080
```

浏览器打开 `http://localhost:3006`，登录账号：**admin / admin123**（后端用户表为空时兼容演示账号；首次启动 `DemoDataInitializer` 自动预置）。

### 配置项说明（application.yml / application-*.yml）

| 配置 | 说明 | 默认 |
|---|---|---|
| `app.approval-mode` | 审批模式：`MANUAL` 人工 / `AUTO` 自动 | `MANUAL` |
| `app.channel.active` | 通道：`mock`（演示） / `wecom`（真实，未联调时报明确错误） | `mock` |
| `app.mock.enabled` | 是否开启 Mock 自动流水线（每分钟注入一条） | `true` |
| `app.compliance.sensitive-words` | 敏感词列表，命中即拦截自动发送 | 内置演示词表 |
| `app.compliance.blocked-patterns` | 正则规则（如身份证号 `\b\d{15,18}\b`），命中即 BLOCKED | 内置 1 条 |
| `app.llm.mock` | `true`=规则化演示模型（无需 Key）；`false`=走 HTTP 模型 | `true` |
| `app.llm.base-url / api-key / model` | OpenAI 兼容模型接入（如 DeepSeek，自动拼 `/chat/completions`） | 空 |
| `app.quote.http.enabled` | `true`=对接真实报价系统（HttpQuoteService），`false`=Mock | `false` |
| `app.wecom.rate-limit-enabled` | 客户级令牌桶限流开关 | `false` |
| `app.wecom.max-outbound-length` | 单条外发最大长度，超出自动分片 | `2048` |
| `app.wecom.corp-id / session-secret / callback-* / agent-id / app-secret` | 真实企微物料（M1 联调用，默认占位） | 占位 |
| `app.screenshot.upload-dir / export-dir` | 截图上传 / 话术导出落盘目录（静态映射 `/uploads/**`） | `./data/screenshot`、`./data/export` |
| `app.screenshot.max-upload-bytes / max-batch-count` | 单张图片大小上限 / 单次批量识别的截图张数上限 | `5MB`、`9` |
| `app.ocr.mode` | OCR 模式：`auto`（本机 tesseract → 外部 OCR 服务 → 占位）/ `external` / `off` | `auto` |
| `app.ocr.tesseract-path / tesseract-lang` | tesseract 可执行文件绝对路径 / 语言包参数 | `C:/Program Files/Tesseract-OCR/tesseract.exe`、`chi_sim+eng` |
| `app.ocr.external-base-url / external-api-key` | 外部 OCR 服务根地址（POST `/ocr`）/ 可选鉴权 Key | 空 |

### 接口概览

| 分组 | 方法 / 路径 | 说明 |
|---|---|---|
| auth | `POST /api/auth/login`、`POST /api/auth/register`、`GET /api/user/info` | 登录 / 注册 / 当前用户信息 |
| sales | `GET /api/customers` | 客户列表 |
| sales | `GET /api/customers/{contactId}/context` | 客户完整上下文（画像 + 阶段 + 最近对话） |
| sales | `GET /api/customers/{contactId}/messages`、`POST /api/customers/{contactId}/send` | 客户消息 / 手动发送 |
| sales | `GET /api/deals`、`POST /api/deals/{dealId}/transition` | 商机列表 / 阶段流转 |
| sales | `GET /api/drafts/pending`、`GET /api/drafts` | 待审批草稿 / 全部草稿 |
| sales | `POST /api/drafts/{id}/approve`、`POST /api/drafts/{id}/reject` | 人工确认发送 / 驳回 |
| sales | `GET /api/logs` | 动作 / 审计日志 |
| sales | `GET /api/strategies` | 策略配置列表 |
| screenshot | `POST /api/screenshot/upload` | 批量上传聊天截图（`file` 字段可多张）并逐张 OCR 识别 |
| screenshot | `POST /api/screenshot/process` | 以确认后的聊天文本复用编排生成话术草稿 |
| screenshot | `GET /api/screenshot/drafts/{id}/export?format=txt\|docx` | 导出话术草稿为 .txt / .docx 文件 |
| sales(demo) | `GET /api/demo/status`、`POST /api/demo/inject`、`POST /api/demo/inject-next` | 演示流水状态 / 注入消息 / 播放下一条剧本 |
| system-manage | `GET /api/user/list`、`POST/PUT/DELETE /api/user[/{id}]` | 用户管理 |
| system-manage | `GET /api/role/list`、`POST/PUT/DELETE /api/role[/{id}]`、`GET|PUT /api/role/{id}/permissions` | 角色与权限管理 |
| system-manage | `GET /api/v3/system/menus/simple`、`POST/PUT/DELETE /api/menu[/{id}]` | 菜单管理 |
| wecom 回调 | `GET|POST /wecom/callback` | 企微消息回调（验签 + ack + 投递） |

### 截图工作流（聊天截图 → OCR → 话术）

面向「手头只有一张聊天截图、想快速得到对应话术」的场景，前端入口 `/sales/screenshot`（前端 README 有完整使用说明）。完整链路：

**上传截图 → OCR 识别 → 文本确认 / 合并 → 复用 agent 编排生成话术 → 一键复制 / 导出**

1. **上传**：`POST /api/screenshot/upload`（multipart，字段名 `file`，可一次传多张，最多 `app.screenshot.max-batch-count` 张）。每张图片落盘到 `app.screenshot.upload-dir` 并通过静态映射 `/uploads/screenshot/**` 可回显，随后立即逐张 OCR，响应为 `{ count, items: [{ fileName, imageUrl, sizeBytes, ocr: { mode, modeLabel, text, detail } }] }`。
2. **识别**：OCR 采用三级路由（`ScreenshotOcrService`）：
   - `mode=auto`（默认）：先探测本机 tesseract CLI（版本探测 + 首张识别探测，结果缓存），可用即返回 **TESSERACT** 结果；否则尝试外部 OCR 服务（`app.ocr.external-base-url`）；仍不可用则返回 **PLACEHOLDER** 占位文本，由前端手动粘贴 / 编辑，保证整条工作流不因缺 OCR 阻断。
   - `mode=external`：强制走外部服务；`mode=off`：关闭识别，一律返回占位。
   - 本机已集成 **Tesseract 5.4（含 chi_sim 简体中文包）**，`app.ocr.tesseract-path` 已指到绝对路径 `C:/Program Files/Tesseract-OCR/tesseract.exe`，`tesseract-lang=chi_sim+eng`——默认即真实 OCR，不依赖外部服务。
   - **空文本安全护栏**：某张图未识别到文字时仍返回 `mode=TESSERACT, text=""`（而非误判引擎不可用降级到占位），由前端提示"未识别到文字，可手动输入或换图"；PLACEHOLDER 分支则提示"OCR 未就绪，可手动输入"。空文本不会直接进入 AI 编排链路。
3. **生成**：前端把确认 / 合并后的聊天文本（含图片对应客户）提交 `POST /api/screenshot/process`；后端将其组装为系统消息，**沿用 `SalesAgentOrchestrator.onMessage` 既有编排链路**（画像 → 阶段 → 策略 → 合规校验 → 入锁），生成 `reply_draft` 话术草稿。
4. **复制 / 导出**：草稿内容可一键复制；`GET /api/screenshot/drafts/{id}/export?format=txt|docx` 可导出为 .txt（UTF-8 纯文本）或 .docx（JDK 自带 ZipOutputStream 手写最小合法 WordprocessingML，零第三方依赖），落盘 `app.screenshot.export-dir`，经 `/uploads/export/**` 下载。

### 接入真实企业微信联调（M1）

真实接入代码已完成并通过本地单测（自造样例验证算法），剩余工作：

1. 老板在企微管理后台准备物料：`corpId`、会话存档 `secret`、RSA 私钥（PKCS1 PEM）、回调 `Token` / `EncodingAESKey`、应用 `agentId` / `app-secret`；
2. 填入 `application-wecom.yml`（或环境变量注入，私钥严禁入库入 git，生产走 KMS）；
3. 以 `mysql,wecom` profile 启动：`mvn spring-boot:run -Dspring-boot.run.profiles=mysql,wecom`；
4. 将回调 URL `https://你的域名/wecom/callback` 配置到企微后台并完成可信 IP / 客户联系权限授权。

接入后 `app.wecom.enabled=true` 会注册 WeComChannel / 回调 Controller / 存档定时拉取（默认 10s 首拉、每 60s 增量）；`app.channel.active=wecom` 时外发走 `message/send`。未填凭据时启动不报错，回调返回失败提示、拉取自动跳过。联调注意：企微对主动外发有 48 小时会话窗口等限制，上线前务必确认授权并保持 `MANUAL` 审批兜底。

### 如何扩展

- **新增回复策略**：在 `StrategyConfig.actionType` 增加枚举值，并在编排器对应分支按既有 `ActionLogger` / `ReplyDraft` 风格补充动作逻辑；
- **接入真实 LLM**：`app.llm.mock=false` + 填 `base-url / api-key / model`（OpenAI 兼容，如 DeepSeek），`HttpLLMClient` 已实现；
- **接入报价 / CRM**：实现 `QuoteService` 接口（HTTP / MQ 均可），或开启 `HttpQuoteService`（`app.quote.http.enabled=true`）；
- **合规规则外置**：在 `app.compliance.sensitive-words` / `blocked-patterns` 追加词条与正则，`pattern-rule-names` 与之一一对应。

## M0：零依赖演示跑法

前置：JDK 17+、Maven 3.8+（无真实企微账号、无 MySQL、无 LLM Key）。

```bash
cd wechat-work-sales-agent
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
- 频率限制（客户级令牌桶）已实现并可在 `application.yml` 配置（`app.wecom.rate-limit-enabled` 等，默认关闭不影响演示；`DESIGN.md §7.4`），上线生产前仍建议人工审批兜底。
- 语音 / 企微消息内的图片等非文本消息 M1 仅落日志跳过：真实语音识别（ASR）与企微媒体下载转文本仍需外部物料联调（TODO）；「聊天截图」场景已通过截图工作流（本机 tesseract OCR）落地，见上文「截图工作流」章节。
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
wechat-work-sales-agent/
├── pom.xml
├── DESIGN.md
└── src/main/
    ├── java/com/example/wechatsales/
    │   ├── WechatSalesApplication.java
    │   ├── config/AppProperties.java, DemoDataInitializer.java, CallbackExecutorConfig.java
    │   ├── channel/Channel.java, MessageBus.java, MessageListener.java,
    │   │         Message.java, OutboundMessage.java, SendResult.java,
    │   │         MockChannel.java, WeComChannel.java, WeComApiClient.java,
    │   │         OutboundSender.java, RateLimitService.java, RoomMemberResolver.java,
    │   │         WeComCallbackXml.java, WeComArchivePuller.java,
    │   │         ArchiveChatRecord.java, ArchiveMessageMapper.java
    │   ├── context/ContextStore.java, ContactContext.java, ContextAssemblyService.java,
    │   │           CustomerProfileService.java, ProfileRuleExtractor.java
    │   ├── crypto/AesCbc.java, WXBizMsgCrypt.java, ArchiveDecryptor.java
    │   ├── stage/StageClassifier.java, RuleStageClassifier.java, StageMachine.java,
    │   │         DealService.java
    │   ├── strategy/ReplyStrategy... / StrategyService.java, LLMClient.java,
    │   │             MockLLMClient.java, HttpLLMClient.java, ComplianceFilter.java,
    │   │             DraftApprovalService.java, SalesAgentOrchestrator.java
    │   ├── action/QuoteService.java, MockQuoteService.java, HttpQuoteService.java,
    │   │          QuoteResult.java, QuoteRequest.java, ActionLogger.java
    │   ├── service/SystemAdminService.java（系统管理域用户/角色/菜单权限）
    │   ├── screenshot/ScreenshotService.java, ScreenshotOcrService.java,
    │   │          OcrMode.java, OcrResult.java（截图工作流）
    │   ├── domain/  (实体 + 枚举 + Repository)
    │   ├── rest/AdminController.java, AuthController.java, SystemAdminController.java,
    │   │          ScreenshotController.java, ApiResponse.java
    │   ├── web/WeComCallbackController.java（企微回调接口）
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
