-- =====================================================================
-- wechat_sales 数据库建表脚本（MySQL 5.7+/8.x）
-- 执行方式：mysql -uroot -p wechat_sales < schema.sql
-- 也可通过 application-mysql.yml 的 spring.sql.init.mode=always 自动执行
-- =====================================================================
CREATE DATABASE IF NOT EXISTS wechat_sales DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE wechat_sales;

-- 客户联系人（对应企微“客户联系”里的 external_userid）
DROP TABLE IF EXISTS contact;
CREATE TABLE contact (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    external_user_id VARCHAR(128) NOT NULL COMMENT '企微客户 external_userid',
    name             VARCHAR(128) NOT NULL COMMENT '客户姓名/备注名',
    company          VARCHAR(255) DEFAULT NULL COMMENT '公司',
    remark           VARCHAR(500) DEFAULT NULL COMMENT '备注',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_contact_external_user_id (external_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户联系人';

-- 客户画像（长期阶段摘要 + 需求 + 偏好，随对话逐步沉淀）
DROP TABLE IF EXISTS customer_profile;
CREATE TABLE customer_profile (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    contact_id       BIGINT        NOT NULL COMMENT 'contact.id',
    stage_summary    VARCHAR(2000) DEFAULT NULL COMMENT '长期阶段摘要（由 Agent 在关键节点生成）',
    needs_summary    VARCHAR(2000) DEFAULT NULL COMMENT '客户需求摘要',
    preferred_topics VARCHAR(1000) DEFAULT NULL COMMENT '偏好话题/关注点',
    risk_notes       VARCHAR(1000) DEFAULT NULL COMMENT '风险备注（合规、比价、异议）',
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_profile_contact (contact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户画像';

-- 消息流水（会话存档落地：IN=客户来消息，OUT=Agent/人工发出）
DROP TABLE IF EXISTS message_log;
CREATE TABLE message_log (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    msg_id       VARCHAR(128)  NOT NULL COMMENT '企微消息 msgId（去重键）',
    contact_id   BIGINT        NOT NULL COMMENT 'contact.id',
    direction    VARCHAR(16)   NOT NULL COMMENT 'IN/OUT',
    sender_type  VARCHAR(16)   NOT NULL COMMENT 'CUSTOMER/AGENT',
    content      VARCHAR(4000) NOT NULL COMMENT '消息文本',
    msg_type     VARCHAR(32)   DEFAULT 'text' COMMENT '消息类型 text/image/...',
    channel_type VARCHAR(32)   DEFAULT 'wecom' COMMENT '来源通道 mock/wecom',
    processed    TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否已被 Agent 处理',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消息时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_msg_id (msg_id),
    KEY idx_message_contact (contact_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息流水';

-- 商机/销售阶段
DROP TABLE IF EXISTS deal;
CREATE TABLE deal (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    contact_id    BIGINT        NOT NULL COMMENT 'contact.id',
    deal_name     VARCHAR(255)  NOT NULL COMMENT '商机名',
    stage         VARCHAR(32)   NOT NULL DEFAULT 'LEAD_INITIAL' COMMENT 'LEAD_INITIAL/QUALIFIED/NEEDS_ANALYSIS/PROPOSAL/NEGOTIATION/WON/LOST',
    amount        DECIMAL(18,2) DEFAULT NULL COMMENT '预估金额',
    description   VARCHAR(1000) DEFAULT NULL COMMENT '商机描述',
    opened_at     DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    closed_at     DATETIME      DEFAULT NULL COMMENT '赢单/输单时间',
    closed_reason VARCHAR(500)  DEFAULT NULL COMMENT '输单原因',
    PRIMARY KEY (id),
    KEY idx_deal_contact (contact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商机/销售阶段';

-- 待审批话术草稿（Agent 生成 -> 人工确认 -> 发送）
DROP TABLE IF EXISTS reply_draft;
CREATE TABLE reply_draft (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    contact_id       BIGINT        NOT NULL COMMENT 'contact.id',
    source_msg_id    VARCHAR(128)  DEFAULT NULL COMMENT '触发消息 msgId',
    stage            VARCHAR(32)   NOT NULL COMMENT '草稿对应销售阶段',
    strategy_name    VARCHAR(128)  NOT NULL COMMENT '命中的策略名',
    action_type      VARCHAR(32)   NOT NULL DEFAULT 'SEND_TEXT' COMMENT 'SEND_TEXT/CREATE_QUOTE/...',
    content          VARCHAR(2000) NOT NULL COMMENT '话术内容',
    reason           VARCHAR(500)  DEFAULT NULL COMMENT '策略命中理由',
    status           VARCHAR(32)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/SENT/BLOCKED',
    blocked_reason   VARCHAR(500)  DEFAULT NULL COMMENT '合规阻断原因',
    quote_reference  VARCHAR(128)  DEFAULT NULL COMMENT '若 actionType=CREATE_QUOTE，报价单号',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    decided_at       DATETIME      DEFAULT NULL COMMENT '人工决定时间',
    sent_at          DATETIME      DEFAULT NULL COMMENT '实际发送时间',
    PRIMARY KEY (id),
    KEY idx_draft_contact (contact_id),
    KEY idx_draft_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='话术审批草稿';

-- 回复策略配置（与 resources/strategies/*.json 结构一致，可按阶段+关键词配置）
DROP TABLE IF EXISTS strategy_config;
CREATE TABLE strategy_config (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    stage            VARCHAR(32)   NOT NULL COMMENT '销售阶段',
    rule_name        VARCHAR(128)  NOT NULL COMMENT '策略名',
    trigger_keywords VARCHAR(1000) DEFAULT NULL COMMENT '触发关键词（|分隔，空=兜底）',
    action_type      VARCHAR(32)   NOT NULL DEFAULT 'SEND_TEXT' COMMENT '动作类型',
    template_content VARCHAR(2000) NOT NULL COMMENT '话术模板（支持 {{customerName}} 等占位符）',
    priority         INT           NOT NULL DEFAULT 100 COMMENT '优先级（数字小先命中）',
    enabled          TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回复策略配置';

-- 报价动作（对接自有业务系统的 SPI 演示）
DROP TABLE IF EXISTS quote_request;
CREATE TABLE quote_request (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    contact_id   BIGINT        NOT NULL COMMENT 'contact.id',
    product_code VARCHAR(64)   DEFAULT NULL COMMENT '产品编码',
    quantity     INT           NOT NULL DEFAULT 1 COMMENT '数量',
    amount       DECIMAL(18,2) DEFAULT NULL COMMENT '报价金额',
    biz_ref_no   VARCHAR(128)  DEFAULT NULL COMMENT '外部业务系统单号',
    idempotency_key VARCHAR(64) DEFAULT NULL COMMENT '幂等键（UUID，外呼以 Idempotency-Key 头携带）',
    retry_count  INT           NOT NULL DEFAULT 0 COMMENT '已重试次数（不含首次尝试）',
    last_error   VARCHAR(1000) DEFAULT NULL COMMENT '最近一次失败原因',
    max_attempts INT           NOT NULL DEFAULT 3 COMMENT '允许的最大尝试次数（含首次）',
    status       VARCHAR(32)   NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/SYNCED/FAILED',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价请求';

-- 动作日志（外发、报价、阶段跃迁、审批等所有关键动作的审计流）
DROP TABLE IF EXISTS action_log;
CREATE TABLE action_log (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    contact_id  BIGINT        DEFAULT NULL COMMENT 'contact.id',
    action_type VARCHAR(64)   NOT NULL COMMENT 'DRAFT_CREATED/DRAFT_APPROVED/QUOTE_CREATED/MESSAGE_SENT/STAGE_CHANGED/...',
    detail      VARCHAR(2000) DEFAULT NULL COMMENT '动作详情',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_action_contact (contact_id),
    KEY idx_action_type (action_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='动作日志';

-- 会话存档拉取游标（seq）：M1 真实企微接入，单行记录（id=1），
-- 由 WeComArchivePuller 每次 getchatdata 拉取后用回包最大 next_seq 更新。
-- 与 archive_seq 实体 ArchiveSeqState 对齐（列名 seq_cursor 避免 MySQL 保留字）。
DROP TABLE IF EXISTS archive_seq;
CREATE TABLE archive_seq (
    id         BIGINT       NOT NULL COMMENT '固定主键（单企业单实例=1）',
    seq_cursor BIGINT       NOT NULL DEFAULT 0 COMMENT '下一次拉取起点（企微回包最大 next_seq）',
    updated_at DATETIME     DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话存档拉取游标';
