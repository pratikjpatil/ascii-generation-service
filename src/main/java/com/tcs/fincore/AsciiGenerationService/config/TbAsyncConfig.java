package com.tcs.fincore.AsciiGenerationService.config;

import java.util.concurrent.*;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/*
   ThreadPoolTaskExecutor configuration for processing the async report generation.
 */
@Configuration
@EnableAsync
public class TbAsyncConfig {

    @Bean("tbTaskExecutor")
    Executor executor(){
        return Executors.newVirtualThreadPerTaskExecutor();
    }
/*
ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(15);        // Minimum threads always alive
        executor.setMaxPoolSize(20);        // Max threads allowed when queue is full
        executor.setQueueCapacity(100);     // Tasks waiting before new threads are created
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(300);
        executor.setThreadNamePrefix("AsyncTB-");
        // Policy when pool and queue are full (e.g., run task in calling thread)
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
 */

}