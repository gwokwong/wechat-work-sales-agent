package com.example.wechatsales.config;

import com.example.wechatsales.domain.Contact;
import com.example.wechatsales.domain.CustomerProfile;
import com.example.wechatsales.domain.Deal;
import com.example.wechatsales.domain.SalesStage;
import com.example.wechatsales.repository.ContactRepository;
import com.example.wechatsales.repository.CustomerProfileRepository;
import com.example.wechatsales.repository.DealRepository;
import com.example.wechatsales.repository.StrategyConfigRepository;
import com.example.wechatsales.strategy.StrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 启动初始化：装载默认回复策略（default.json -> strategy_config）并预置演示客户。
 * 幂等：仅当对应表为空时写入，保证重启不产生重复数据。
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class DemoDataInitializer implements ApplicationRunner {

    private final StrategyService strategyService;
    private final ContactRepository contactRepository;
    private final CustomerProfileRepository profileRepository;
    private final DealRepository dealRepository;
    private final StrategyConfigRepository strategyConfigRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedStrategies();
        seedDemoCustomers();
    }

    private void seedStrategies() {
        if (strategyConfigRepository.count() == 0) {
            int loaded = strategyService.loadStrategiesFromClasspath("strategies/default.json");
            log.info("[seed] 已从 strategies/default.json 装载 {} 条回复策略", loaded);
        } else {
            log.info("[seed] strategy_config 已存在数据，跳过策略装载");
        }
    }

    private void seedDemoCustomers() {
        if (contactRepository.count() > 0) {
            log.info("[seed] contact 已有数据，跳过演示客户创建");
            return;
        }
        Contact a = saveDemoCustomer("wxid_demo_001", "张三", "星河科技", "售前咨询：CRM 选型");
        Contact b = saveDemoCustomer("wxid_demo_002", "李四", "蓝鲸连锁", "售前咨询：门店回访自动化");
        createDeal(a, SalesStage.LEAD_INITIAL, "CRM 选型-星河科技");
        createDeal(b, SalesStage.LEAD_INITIAL, "回访自动化-蓝鲸连锁");
        log.info("[seed] 已预置演示客户：张三(wxid_demo_001)、李四(wxid_demo_002)");
    }

    private Contact saveDemoCustomer(String externalUserId, String name, String company, String remark) {
        Contact c = new Contact();
        c.setExternalUserId(externalUserId);
        c.setName(name);
        c.setCompany(company);
        c.setRemark(remark);
        c.touch();
        Contact saved = contactRepository.save(c);

        CustomerProfile profile = new CustomerProfile();
        profile.setContactId(saved.getId());
        profile.setStageSummary("新客户首次接入");
        profile.setNeedsSummary(remark);
        profile.touch();
        profileRepository.save(profile);
        return saved;
    }

    private void createDeal(Contact contact, SalesStage stage, String dealName) {
        if (dealRepository.findByContactId(contact.getId()).isPresent()) {
            return;
        }
        Deal deal = new Deal();
        deal.setContactId(contact.getId());
        deal.setDealName(dealName);
        deal.setStage(stage);
        deal.setDescription("演示预置商机");
        deal.setOpenedAt(java.time.LocalDateTime.now());
        deal.touch();
        dealRepository.save(deal);
    }
}
