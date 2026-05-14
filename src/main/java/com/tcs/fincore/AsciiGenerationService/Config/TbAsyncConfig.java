package com.tcs.fincore.AsciiGenerationService.Config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/*
   ThreadPoolTaskExecutor configuration for processing the async report generation.
 */
@Configuration
@EnableAsync
public class TbAsyncConfig {

    @Bean("tbTaskExecutor")
    ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(30);        // Minimum threads always alive
        executor.setMaxPoolSize(60);        // Max threads allowed when queue is full
        executor.setQueueCapacity(100);     // Tasks waiting before new threads are created
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(300);
        executor.setThreadNamePrefix("AsyncTB-");
        // Policy when pool and queue are full (e.g., run task in calling thread)
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

//    @Bean("submitExecutor")
//    ThreadPoolExecutor submitExecutor() {
//        ThreadPoolExecutor submitExecutor=
////    		Executors.newSingleThreadExecutor();
//                new ThreadPoolExecutor(
//                        1,
//                        1,
//                        0L,
//                        TimeUnit.SECONDS,
//                        new ArrayBlockingQueue<>(3),
//                        new ThreadPoolExecutor.AbortPolicy() // Throws RejectedExecutionException
//                );
//        return submitExecutor;
//    }

}