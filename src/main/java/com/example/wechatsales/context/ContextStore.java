package com.example.wechatsales.context;

import com.example.wechatsales.domain.Contact;
import com.example.wechatsales.domain.CustomerProfile;
import com.example.wechatsales.domain.Deal;
import com.example.wechatsales.domain.MessageLog;
import com.example.wechatsales.domain.Direction;
import com.example.wechatsales.exception.NotFoundException;
import com.example.wechatsales.repository.ContactRepository;
import com.example.wechatsales.repository.CustomerProfileRepository;
import com.example.wechatsales.repository.DealRepository;
import com.example.wechatsales.repository.MessageLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 上下文存储：按客户聚合「短期最近N轮消息 + 客户画像 + 商机」，
 * 为上下文组装与长期摘要沉淀提供数据源。
 */
@Service
@RequiredArgsConstructor
public class ContextStore {

    /** 短期记忆轮数上限 */
    public static final int DEFAULT_RECENT_LIMIT = 10;

    private final ContactRepository contactRepository;
    private final CustomerProfileRepository profileRepository;
    private final DealRepository dealRepository;
    private final MessageLogRepository messageLogRepository;

    public Contact getOrCreateContact(String externalUserId) {
        Optional<Contact> existed = contactRepository.findByExternalUserId(externalUserId);
        if (existed.isPresent()) {
            return existed.get();
        }
        Contact contact = new Contact();
        contact.setExternalUserId(externalUserId);
        contact.setName(externalUserId.startsWith("wxid_")
                ? "客户-" + externalUserId.substring(Math.max(0, externalUserId.length() - 4))
                : externalUserId);
        contact.setCompany("");
        contact.setRemark("首次会话自动建档");
        contact.touch();
        return contactRepository.save(contact);
    }

    /** 按内部 id 取联系人（不存在则抛 404） */
    public Contact getOrCreateContactByInternalId(Long contactId) {
        return contactRepository.findById(contactId)
                .orElseThrow(() -> new NotFoundException("联系人不存在 contactId=" + contactId));
    }

    public CustomerProfile getOrCreateProfile(Long contactId) {
        return profileRepository.findByContactId(contactId).orElseGet(() -> {
            CustomerProfile profile = new CustomerProfile();
            profile.setContactId(contactId);
            profile.setStageSummary("新客户首次接入");
            profile.touch();
            return profileRepository.save(profile);
        });
    }

    public Optional<Deal> findDeal(Long contactId) {
        return dealRepository.findByContactId(contactId);
    }

    public List<String> recentCustomerMessages(Long contactId, int limit) {
        List<MessageLog> logs = messageLogRepository
                .findTop10ByContactIdAndDirectionOrderByCreatedAtDesc(contactId, Direction.IN.name());
        if (logs.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> contents = new ArrayList<>();
        int max = Math.min(limit <= 0 ? DEFAULT_RECENT_LIMIT : limit, logs.size());
        // 返回顺序调整为 旧 → 新，便于 LLM 理解对话脉络
        for (int i = max - 1; i >= 0; i--) {
            contents.add(logs.get(i).getContent());
        }
        return contents;
    }
}
