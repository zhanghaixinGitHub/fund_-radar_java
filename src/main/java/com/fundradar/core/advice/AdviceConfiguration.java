package com.fundradar.core.advice;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 推理和到期核验用单独的有界线程，避免占用既有每分钟账本结算线程。 */
@Configuration
public class AdviceConfiguration {
    @Bean("taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("fund-scheduled-");
        return scheduler;
    }
    @Bean("adviceTaskScheduler")
    public ThreadPoolTaskScheduler adviceTaskScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("portfolio-advice-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true); scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
