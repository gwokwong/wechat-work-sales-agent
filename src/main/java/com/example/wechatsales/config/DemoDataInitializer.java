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
    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final SysMenuRepository menuRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedStrategies();
        seedDemoCustomers();
        seedSystemAdmin();
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

    // ---------- 系统管理域种子 ----------

    private void seedSystemAdmin() {
        if (userRepository.count() == 0) {
            SysUser admin = new SysUser();
            admin.setUserName("admin");
            admin.setNickName("超级管理员");
            admin.setPassword(SystemAdminService.encodePassword("admin123"));
            admin.setGender("男");
            admin.setStatus("1");
            admin.setRoles(java.util.List.of("R_SUPER", "R_ADMIN"));
            admin.touch();
            userRepository.save(admin);
            log.info("[seed] 已预置系统用户 admin/admin123");
        } else {
            log.info("[seed] sys_user 已有数据，跳过系统用户创建");
        }

        if (roleRepository.count() == 0) {
            saveRole("超级管理员", "R_SUPER", "拥有全部系统管理权限", true);
            saveRole("系统管理员", "R_ADMIN", "管理角色/菜单/用户", true);
            saveRole("销售", "R_SALES", "普通销售人员", true);
            log.info("[seed] 已预置演示角色 R_SUPER / R_ADMIN / R_SALES");
        }

        if (menuRepository.count() == 0) {
            seedMenus();
            log.info("[seed] 已预置系统管理菜单树");
        }
    }

    private void saveRole(String roleName, String roleCode, String description, boolean enabled) {
        SysRole role = new SysRole();
        role.setRoleName(roleName);
        role.setRoleCode(roleCode);
        role.setDescription(description);
        role.setEnabled(enabled);
        role.touch();
        roleRepository.save(role);
    }

    private void seedMenus() {
        Long console = saveMenu(0L, "console", "/console", "/console/index", "控制台", "mdi:desktop-mac", 1);
        Long customer = saveMenu(0L, "customer", "/customer", null, "客户管理", "mdi:account-group", 2);
        Long deal = saveMenu(0L, "deal", "/deal", null, "商机管理", "mdi:chart-box", 3);
        Long draft = saveMenu(0L, "draft", "/draft", null, "话术库", "mdi:message-text", 4);
        Long screenshot = saveMenu(0L, "screenshot", "/screenshot", null, "截图工作流", "mdi:image-multiple", 5);
        Long demo = saveMenu(0L, "demo", "/demo", null, "演示中心", "mdi:flask", 6);
        Long system = saveMenu(0L, "system", "/system", null, "系统管理", "mdi:cog", 7);

        saveMenu(customer, "customer-list", "list", "/customer/list", "客户列表", "", 1);
        saveMenu(customer, "customer-import", "list", "/customer/import", "导入客户", "", 2);
        saveMenu(deal, "deal-list", "list", "/deal/list", "商机列表", "", 1);
        saveMenu(draft, "draft-list", "list", "/draft/list", "话术列表", "", 1);
        saveMenu(screenshot, "screenshot-index", "list", "/screenshot/index", "截图识别", "", 1);
        saveMenu(demo, "demo-list", "list", "/demo/index", "演示数据", "", 1);

        Long userMenu = saveMenu(system, "system-user", "User", "/system/user", "用户管理", "mdi:account", 1);
        Long roleMenu = saveMenu(system, "system-role", "Role", "/system/role", "角色管理", "mdi:shield-account", 2);
        Long menuMenu = saveMenu(system, "system-menu", "Menu", "/system/menu", "菜单管理", "mdi:menu", 3);

        // 角色管理页权限按钮
        saveButton(roleMenu, "role:add", "新增", 1);
        saveButton(roleMenu, "role:edit", "编辑", 2);
        saveButton(roleMenu, "role:delete", "删除", 3);
        saveButton(roleMenu, "role:permission", "分配权限", 4);
        // 菜单管理页权限按钮
        saveButton(menuMenu, "menu:add", "新增", 1);
        saveButton(menuMenu, "menu:edit", "编辑", 2);
        saveButton(menuMenu, "menu:delete", "删除", 3);
    }

    /** 保存菜单节点，返回生成的 id（需先存父再存子） */
    private Long saveMenu(Long parentId, String name, String label, String path, String title, String icon, int sort) {
        SysMenu menu = new SysMenu();
        menu.setParentId(parentId);
        menu.setName(name);
        menu.setPath(path);
        menu.setTitle(title);
        menu.setIcon(icon);
        menu.setSort(sort);
        menu.setMenuType("menu");
        menu.setIsMenu(true);
        menu.touch();
        return menuRepository.save(menu).getId();
    }

    private void saveButton(Long parentId, String authMark, String title, int sort) {
        SysMenu button = new SysMenu();
        button.setParentId(parentId);
        button.setName(parentId + "_" + authMark);
        button.setTitle(title);
        button.setAuthMark(authMark);
        button.setAuthSort(sort);
        button.setMenuType("button");
        button.setIsAuthButton(true);
        button.touch();
        menuRepository.save(button);
    }
}
