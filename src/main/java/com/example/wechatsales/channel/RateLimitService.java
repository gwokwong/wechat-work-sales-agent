package com.example.wechatsales.channel;

import com.example.wechatsales.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户级发送频率限制（令牌桶，DESIGN.md §7.4）。
 * <p>
 * 对每个 externalUserId 维护独立令牌桶：桶容量=可立即连续发送条数；
 * 令牌按固定速率补充（ratePerSecond），补充量=(now-lastRefillMs)*ratePerSecond，
 * 封顶容量，避免空闲后积攒过多令牌。
 * 全局开关 {@code app.wecom.rate-limit-enabled=false}（默认）时 tryAcquire 恒返回 true，不影响 M0 演示。
 */
@Component
public class RateLimitService {

    private final AppProperties appProperties;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * 尝试为指定外部客户获取一个发送令牌。
     *
     * @param externalUserId 企微 external_userid
     * @return true=允许发送；false=触发频率限制
     */
    public boolean tryAcquire(String externalUserId) {
        if (!appProperties.getWecom().isRateLimitEnabled()) {
            return true;
        }
        double perSecond = appProperties.getWecom().getRateLimitPerSecond();
        int capacity = appProperties.getWecom().getRateLimitCapacity();
        TokenBucket bucket = buckets.computeIfAbsent(externalUserId,
                key -> new TokenBucket(capacity, perSecond));
        return bucket.tryAcquire();
    }

    /**
     * 简单令牌桶。非线程安全由外部 ConcurrentHashMap + 单线程内调用保证；
     * 单桶操作使用 synchronized 保证同一客户并发发送时的正确性。
     */
    static class TokenBucket {
        private final int capacity;
        private final double perSecond;
        private double tokens;
        private long lastRefillMs = System.currentTimeMillis();

        TokenBucket(int capacity, double perSecond) {
            this.capacity = Math.max(1, capacity);
            this.perSecond = Math.max(0, perSecond);
            this.tokens = this.capacity; // 初始满桶：允许客户首次连续发送 capacity 条
        }

        synchronized boolean tryAcquire() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            double gained = (now - lastRefillMs) / 1000.0 * perSecond;
            if (gained > 0) {
                tokens = Math.min(capacity, tokens + gained);
                lastRefillMs = now;
            }
        }
    }
}
