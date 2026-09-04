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
    }

    /** 真实企微接入配置（M1 阶段使用，占位） */
    @Data
    public static class Wecom {
        /** 总开关：false 时 WeComChannel / 回调 Controller / 存档拉取均不启用（启动不报错） */
        private boolean enabled = false;

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
