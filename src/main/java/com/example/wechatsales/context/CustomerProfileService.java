package com.example.wechatsales.context;

import com.example.wechatsales.domain.Contact;
import com.example.wechatsales.domain.CustomerProfile;
import com.example.wechatsales.domain.SalesStage;
import com.example.wechatsales.repository.CustomerProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 客户画像服务：维护 customer_profile —— 短期画像增量更新 + 长期阶段摘要沉淀 +
 * 预算/时间窗规则提取（DESIGN.md §5.3）。
 * 真实 LLM 接入后，可把「对话记录 → 摘要」交给 LLM 压缩，再写回 stageSummary。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerProfileService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final ContextStore contextStore;
    private final CustomerProfileRepository profileRepository;
    private final ProfileRuleExtractor profileRuleExtractor;

    /** 新消息到达后更新画像（增量更新 + 阶段关键节点时沉淀长期摘要） */
    public void updateAfterMessage(Contact contact, String content, SalesStage stage,
                                   boolean stageJustChanged) {
        CustomerProfile profile = contextStore.getOrCreateProfile(contact.getId());

        String text = content == null ? "" : content.trim();
        if (!text.isBlank()) {
            // 简单关键词沉淀：带价格/预算词的记入偏好（价格敏感）；带公司名/规模词的记入需求
            if (text.contains("价格") || text.contains("预算") || text.contains("费用") || text.contains("多少钱")) {
                profile.appendTopic("价格敏感，需重点谈价值");
            }
            if (text.contains("公司") || text.contains("门店") || text.contains("团队") || text.contains("连锁")) {
                profile.appendTopic("组织规模信息：" + (text.length() > 40 ? text.substring(0, 40) + "..." : text));
            }
            // 常规需求片段追加（控制总量由 CustomerProfile#appendNeeds 截断）
            profile.appendNeeds(text.length() > 60 ? text.substring(0, 60) + "…" : text);
        }

        // 规则提取预算范围 / 时间窗口（DESIGN.md §5.3）：命中即覆盖沉淀，无法可靠提取时保留空并记录原因
        ProfileRuleExtractor.ProfileExtraction extraction = profileRuleExtractor.extract(text);
        if (extraction.budgetRange() != null) {
            profile.setBudgetRange(extraction.budgetRange());
        } else if (extraction.budgetSkipReason() != null) {
            log.info("[画像] contactId={} 预算区间未提取（保留空）: {}", contact.getId(), extraction.budgetSkipReason());
        }
        if (extraction.timeWindow() != null) {
            profile.setTimeWindow(extraction.timeWindow());
        } else if (extraction.timeSkipReason() != null) {
            log.info("[画像] contactId={} 时间窗口未提取（保留空）: {}", contact.getId(), extraction.timeSkipReason());
        }

        // 阶段跃迁是长期记忆的关键节点：把时间+阶段写入长期阶段摘要
        if (stageJustChanged || profile.getStageSummary() == null) {
            String stamp = LocalDateTime.now().format(TIME_FMT);
            String oldSummary = profile.getStageSummary();
            String node = stamp + " 进入阶段:" + stage.name() + "(" + stage.displayName() + ")";
            profile.setStageSummary(oldSummary == null || oldSummary.isBlank() || "新客户首次接入".equals(oldSummary)
                    ? node
                    : mergeTruncate(oldSummary, node, 2000));
        }

        profile.touch();
        profileRepository.save(profile);
    }

    /** 手动覆盖长期摘要（如接入 LLM 摘要后调用） */
    public void rewriteStageSummary(Long contactId, String summary) {
        CustomerProfile profile = contextStore.getOrCreateProfile(contactId);
        profile.setStageSummary(summary);
        profile.touch();
        profileRepository.save(profile);
    }

    private String mergeTruncate(String base, String add, int maxLen) {
        String merged = base + "；" + add;
        return merged.length() <= maxLen ? merged : merged.substring(merged.length() - maxLen);
    }
}
