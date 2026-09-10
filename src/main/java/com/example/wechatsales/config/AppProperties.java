package com.example.wechatsales.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用自定义配置（prefix=app）。
 * 涵盖：人工审批开关、通道选择、Mock 演示参数、合规敏感词、真实企微接入占位配置。
 */
@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** 审批模式：MANUAL=人工确认后发送；AUTO=自动发送（仅测试用） */
    private String approvalMode = "MANUAL";

    private Channel channel = new Channel();
    private Mock mock = new Mock();
    private Compliance compliance = new Compliance();
    private Wecom wecom = new Wecom();
    private Quote quote = new Quote();
    private Llm llm = new Llm();
    /** 截图工作流配置（app.screenshot.*） */
    private Screenshot screenshot = new Screenshot();
    /** 截图 OCR 识别配置（app.ocr.*） */
    private Ocr ocr = new Ocr();

    /** 真实报价系统 HTTP 适配配置（app.quote.http.*） */
    @Data
    public static class Quote {
        private Http http = new Http();
    }

    /** HTTP 报价通道：enabled=true 时注册 HttpQuoteService（MockQuoteService 自动停用） */
    @Data
    public static class Http {
        /** true=对接真实报价系统；false=使用 MockQuoteService 演示实现 */
        private boolean enabled = false;
        /** 内部报价系统根地址，如 http://quote.internal.example.com */
        private String baseUrl = "";
        /** 调用报价系统所需鉴权 Key（建议环境变量注入），适配器以 X-API-Key 头发送 */
        private String apiKey = "";
    }

    /** 通道配置 */
    @Data
    public static class Channel {
        /** mock / wecom */
        private String active = "mock";
    }

    /**
     * 截图工作流配置（app.screenshot.*）。
     * 上传目录/导出目录默认落在应用工作目录下的 ./data 内；
     * 上传图片经 OCR（或前端手动确认文本）组装为系统消息后复用既有 agent 编排链路生成话术。
     */
    @Data
    public static class Screenshot {
        /** 上传图片落盘根目录（相对应用工作目录），静态资源映射 /uploads/screenshot/** 指向此处 */
        private String uploadDir = "./data/screenshot";
        /** 话术导出文件落盘目录（相对应用工作目录），静态资源映射 /uploads/export/** 指向此处 */
        private String exportDir = "./data/export";
        /** 上传单张图片大小上限（字节），默认 5MB */
        private long maxUploadBytes = 5L * 1024 * 1024;
        /** 单次批量上传识别的最多截图张数（默认 9） */
        private int maxBatchCount = 9;
        /** 允许的图片扩展名（小写，不含点） */
        private List<String> allowedExtensions = new ArrayList<>(List.of("png", "jpg", "jpeg", "webp", "bmp"));
    }

    /**
     * 截图 OCR 配置（app.ocr.*）。
     * mode：auto（默认）按「本机 tesseract → 外部 OCR 服务 → 占位兜底」顺序探测；
     *       external（强制走外部服务）；off（关闭识别，一律返回占位文本，由前端手动粘贴/编辑）。
     */
    @Data
    public static class Ocr {
        /** auto / external / off */
        private String mode = "auto";
        /** tesseract 可执行文件路径（auto 模式下探测；留空则按 PATH 中的 tesseract 探测） */
        private String tesseractPath = "tesseract";
        /** tesseract 语言包参数，如 chi_sim+eng；缺中文包时可回退 eng */
        private String tesseractLang = "chi_sim+eng";
        /** 外部 OCR 服务根地址（POST {base-url}/ocr，json {"imageBase64":"..."}，返回 {"text":"..."}） */
        private String externalBaseUrl = "";
        /** 外部 OCR 服务鉴权 Key（可选，以 X-API-Key 头发送） */
        private String externalApiKey = "";
    }

    /** Mock 演示配置 */
    @Data
    public static class Mock {
        private boolean enabled = true;
        private long intervalMs = 60_000L;
        private long firstDelayMs = 15_000L;
        private String customerAId = "wxid_demo_001";
        private String customerBId = "wxid_demo_002";
        /** 预置演示剧本（格式 "客户externalUserId|消息内容"，按顺序逐条注入，驱动销售阶段自动流转） */
        private List<String> messages = new ArrayList<>(List.of(
                "wxid_demo_001|你好，我们在选型客户管理系统，想了解下你们方案大概什么价位？",
                "wxid_demo_002|我们连锁门店想做客户回访自动化，有现成的方案可以介绍吗？",
                "wxid_demo_001|价格可接受，能介绍下具体功能和实施周期吗？",
                "wxid_demo_001|好的，麻烦出一份正式的方案和报价单给我们。",
                "wxid_demo_002|算了，我们和另一家合作很久了，这次先不考虑更换，谢谢。",
                "wxid_demo_001|方案和报价收到，整体不错，但价格能再谈一些空间吗？",
                "wxid_demo_001|行，就定你们了，这周我们把合同流程走起来。"
        ));
    }

    /** 合规配置 */
    @Data
    public static class Compliance {
        private List<String> sensitiveWords = new ArrayList<>(List.of(
                "保证", "承诺100%", "返现", "私下转账", "绝对", "百分百", "竞品XX", "免费送"
        ));
        /** 单条话术最大长度 */
        private int maxContentLength = 2000;
        /** 正则表达式列表，命中即阻断（如银行卡/身份证号等号码外泄） */
        private List<String> blockedPatterns = new ArrayList<>();
        /** 与 blockedPatterns 一一对应的规则名（英文逗号分隔）；为空时阻断原因直接用正则原文 */
        private String patternRuleNames = "";
    }

    /** LLM 话术生成配置（app.llm.*）：mock=true 走 MockLLMClient（演示默认）；false 走 OpenAI 兼容 HTTP 客户端 */
    @Data
    public static class Llm {
        /** true=MockLLMClient（规则个性化演示，不依赖外部模型）；false=HttpLLMClient（OpenAI 兼容 chat/completions） */
        private boolean mock = true;
        /** OpenAI 兼容根地址，如 https://api.deepseek.com/v1（自动拼接 /chat/completions） */
        private String baseUrl = "";
        /** API Key（建议环境变量注入，如 ${DEEPSEEK_API_KEY:}），请求以 Bearer 头发送 */
        private String apiKey = "";
        /** 模型名，如 deepseek-chat */
        private String model = "";
        /** 采样温度（0~1，越高越发散） */
        private double temperature = 0.7;
        /** HTTP 连接/读取超时（秒），预留字段；当前随 Spring Boot RestClient 默认配置生效 */
        private int timeoutSeconds = 30;
    }

    /** 真实企微接入配置（M1 阶段使用，占位） */
    @Data
    public static class Wecom {
        /** 总开关：false 时 WeComChannel / 回调 Controller / 存档拉取均不启用（启动不报错） */
        private boolean enabled = false;

        // ---- 外发频率限制（客户级令牌桶，DESIGN.md §7.4；默认关闭，不影响 M0 演示）----
        /** 是否启用客户级发送频率限制 */
        private boolean rateLimitEnabled = false;
        /** 令牌桶容量：单客户可立即连续发送的条数 */
        private int rateLimitCapacity = 5;
        /** 令牌补充速率：每秒补充条数（默认 0.05 ≈ 每 20 秒补 1 个令牌） */
        private double rateLimitPerSecond = 0.05;

        // ---- 外发长文本分片（DESIGN.md §10 消息分片/长文本处理）----
        /** 单条外发最大字符数：超过则按段落/换行边界拆分为多条顺序发送（企微文本消息建议上限 2048） */
        private int maxOutboundLength = 2048;

        private String corpId = "";
        private String sessionSecret = "";
        private String callbackToken = "";
        private String callbackAesKey = "";
        private String sessionArchivePrivateKey = "";
        private String agentId = "";
        private String appSecret = "";

        // ---- 会话存档拉取 ----
        /** 是否启用 @Scheduled 增量拉取（仅当 enabled=true 生效） */
        private boolean archivePullEnabled = true;
        /** 单次拉取 limit（官方上限 1000） */
        private int archivePullLimit = 1000;
        private long archivePullInitialDelayMs = 10_000L;
        private long archivePullFixedDelayMs = 60_000L;

        // ---- 消息方向识别辅助 ----
        /** 企业内部成员 userid 列表（会话存档方向判断；留空则按 externalIdPrefixes 启发式判断） */
        private List<String> internalUserIds = new ArrayList<>();
        /** 企微外部联系人 id 常见前缀（wm=微信用户外部联系人 / wo=企业微信外部联系人 等） */
        private List<String> externalIdPrefixes = new ArrayList<>(List.of("wm", "wo", "wxid_", "wx_"));

        // ---- 外发映射 ----
        /** external_userid → 企微成员 userid（message/send 的 touser）；缺失时按原值直发并告警 */
        private Map<String, String> externalToUserid = new HashMap<>();

        // ---- 客户群「群成员 → 外部客户」映射（DESIGN.md §10；RoomMemberResolver 已实现实时+静态回落）----
        /** 静态映射兜底：roomid → 群内外部客户 external_userid 列表（演示/无法调用企微 API 时使用） */
        private Map<String, List<String>> roomExternalMembers = new HashMap<>();
        /**
         * 是否实时调用 externalcontact/groupchat/get 解析群内外部成员（需「客户联系」权限，
         * 使用 app-secret 的 access_token）；失败自动回落静态映射。默认 true。
         */
        private boolean roomMemberLiveResolveEnabled = true;

        /** 解析 message/send 的 touser：优先映射表，否则原 external_userid */
        public String resolveTouser(String externalUserId) {
            String mapped = externalToUserid.get(externalUserId);
            return mapped == null || mapped.isBlank() ? externalUserId : mapped;
        }

        /** 是否为企微外部联系人 id（用于会话存档消息方向判断） */
        public boolean looksExternal(String id) {
            if (id == null || id.isBlank()) {
                return false;
            }
            if (internalUserIds.contains(id)) {
                return false;
            }
            for (String prefix : externalIdPrefixes) {
                if (id.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
    }
}
