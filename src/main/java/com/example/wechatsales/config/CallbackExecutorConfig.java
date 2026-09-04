package com.example.wechatsales.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 企微回调异步投递线程池。
 *
 * <p>硬约束：回调必须 5 秒内 ack，消息处理必须异步。队列较大以容忍瞬时并发；
 * 若任务被拒绝（RejectedExecutionException），仅记日志不阻塞 ack——回调丢消息由
 * 「会话存档定时拉取」兜底补齐（这正是同时实现两路消息入口的原因）。</p>
 */
@Configuration
public class CallbackExecutorConfig {

    @Bean(name = "wecomCallbackExecutor")
    public ThreadPoolTaskExecutor wecomCallbackExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("wecom-cb-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
