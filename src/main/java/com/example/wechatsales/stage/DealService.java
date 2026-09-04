package com.example.wechatsales.stage;

import com.example.wechatsales.domain.Contact;
import com.example.wechatsales.domain.Deal;
import com.example.wechatsales.domain.SalesStage;
import com.example.wechatsales.exception.StageTransitionException;
import com.example.wechatsales.repository.DealRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 商机服务：负责每个客户的 Deal 生命周期 —— 找/建商机、推进状态机。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DealService {

    private final DealRepository dealRepository;
    private final StageMachine stageMachine;

    /** 分类器给出建议阶段后推进状态机；非法跃迁保持原阶段并返回说明 */
    public StageUpdateResult applyClassifiedStage(Contact contact, SalesStage suggested) {
        Deal deal = getOrCreateDeal(contact.getId());
        SalesStage before = deal.getStage();

        if (before == suggested) {
            return new StageUpdateResult(deal, false, before, null);
        }
        if (deal.isTerminal()) {
            log.info("商机 Deal#{} 已处于终态 {}，忽略建议阶段 {}", deal.getId(), before, suggested);
            return new StageUpdateResult(deal, false, before, "商机已终态，忽略阶段建议");
        }
        try {
            Deal updated = stageMachine.transition(deal, suggested);
            return new StageUpdateResult(updated, true, before, null);
        } catch (StageTransitionException e) {
            log.info("阶段跃迁被拦截：{}；可在管理端人工推进", e.getMessage());
            return new StageUpdateResult(deal, false, before, e.getMessage());
        }
    }

    public Deal getOrCreateDeal(Long contactId) {
        Optional<Deal> existed = dealRepository.findByContactId(contactId);
        if (existed.isPresent()) {
            return existed.get();
        }
        Deal deal = new Deal();
        deal.setContactId(contactId);
        deal.setDealName("商机-" + contactId + "-" + LocalDateTime.now().toLocalDate());
        deal.setDescription("由演示流水自动创建的商机");
        deal.touch();
        return dealRepository.save(deal);
    }

    /** 阶段推进结果 */
    public record StageUpdateResult(Deal deal, boolean changed, SalesStage fromStage, String blockedReason) {
    }
}
