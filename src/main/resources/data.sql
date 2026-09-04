-- =====================================================================
-- data.sql（可选）：为 MySQL 环境预置回复策略配置。
-- 注意：demo(H2) 环境 spring.sql.init.mode=never，不会执行本文件，
-- 演示策略由 resources/strategies/default.json 在启动时自动装载。
-- 代码会在 strategy_config 为空时才加载 default.json，避免重复数据。
-- =====================================================================
INSERT INTO strategy_config (stage, rule_name, trigger_keywords, action_type, template_content, priority, enabled) VALUES
('LEAD_INITIAL',   '初始接待',            NULL, 'SEND_TEXT',    '您好 {{customerName}}，感谢咨询！我是您的专属顾问小W，方便的话请简单描述一下您的需求，我马上为您对接方案～', 100, 1),
('QUALIFIED',      '需求确认-价格咨询',   '价格|价位|多少钱|预算|报价', 'SEND_TEXT', '您好 {{customerName}}，我们产品按企业规模和模块订阅收费，标准版年费大约 2~8 万不等。请问贵司大概多少人使用？我可以据此给您初步测算。', 10, 1),
('QUALIFIED',      '需求确认-兜底',       NULL, 'SEND_TEXT',    '收到您的消息。为了更准确定位需求，想和您确认三个问题：1) 使用人数规模？2) 主要想解决什么问题？3) 期望的上线时间？', 100, 1),
('NEEDS_ANALYSIS', '方案阶段-需求分析',   '功能|需求|场景|实施|上线|定制', 'SEND_TEXT', '好的 {{customerName}}，我整理了您关心的几个点：功能覆盖、实施周期与定制空间。能否约 30 分钟电话/视频做个需求澄清？结束后我会输出一份《需求分析与实施方案建议》。', 10, 1),
('NEEDS_ANALYSIS', '方案阶段-兜底',       NULL, 'SEND_TEXT',    '明白，您这边关注的更多是具体落地细节，我正在整理需求清单，随后会给到实施方案建议。', 100, 1),
('PROPOSAL',       '提案阶段-发送方案报价','方案|报价单|正式报价|发送方案', 'CREATE_QUOTE', '您好 {{customerName}}，按我们沟通的需求，我准备了正式《产品方案与报价单》。方案亮点：……。报价单号 {{quoteReference}}，请您查收，有任何疑问随时找我！', 10, 1),
('PROPOSAL',       '提案阶段-兜底',       NULL, 'SEND_TEXT',    '方案我正在做最后润色，最晚明天上午前发给您，请稍等片刻。', 100, 1),
('NEGOTIATION',    '谈判阶段-折扣洽谈',   '折扣|优惠|便宜|再让|降价', 'SEND_TEXT', '{{customerName}} 您好，价格方面我们确实还有一定空间。如果贵司能本季度签约并按年付，我可以帮您申请额外优惠，具体幅度我这边确认后答复您。', 10, 1),
('NEGOTIATION',    '谈判阶段-兜底',       NULL, 'SEND_TEXT',    '收到，关于合同商务条款我会和您逐条对齐，确保后续落地顺畅。', 100, 1),
('WON',            '赢单-感谢与推进',     NULL, 'SEND_TEXT',    '太棒了 {{customerName}}！感谢信任，我们马上启动合同与实施流程，稍后项目经理会与您对接。', 10, 1),
('LOST',           '输单-保持联系',       NULL, 'SEND_TEXT',    '收到，理解您的决定 {{customerName}}。后续如有新需求欢迎随时找我，也祝贵司业务顺利！', 10, 1);
