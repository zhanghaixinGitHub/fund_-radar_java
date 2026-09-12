package com.fundradar.core.direction1d;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 1日调度专用线程，不占用持仓建议和模拟结算的线程。 */
@Configuration
public class Direction1dConfiguration {
    @Bean("direction1dTaskScheduler") public ThreadPoolTaskScheduler scheduler() {
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("direction-1d-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);scheduler.setAwaitTerminationSeconds(20);return scheduler;
    }
}
