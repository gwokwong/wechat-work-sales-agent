package com.example.wechatsales;

import com.example.wechatsales.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 企业微信销售 Agent - 应用启动类。
 *
 * <p>M0 演示：默认 profile=demo（H2 + MockChannel + MockLLM + 人工审批），直接
 * {@code mvn spring-boot:run} 即可体验完整演示链路。</p>
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
public class WechatSalesApplication {

    public static void main(String[] args) {
        SpringApplication.run(WechatSalesApplication.class, args);
    }
}
