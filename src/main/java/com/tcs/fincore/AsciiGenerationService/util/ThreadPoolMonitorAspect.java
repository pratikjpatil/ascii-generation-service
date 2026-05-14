package com.tcs.fincore.AsciiGenerationService.util;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadPoolExecutor;

@Aspect
@Component
@Slf4j
public class ThreadPoolMonitorAspect {

    @Autowired
    @Qualifier("tbTaskExecutor")
    private ThreadPoolTaskExecutor taskExecutor; // Ensure you qualify this if you have multiple executors

    // Intercept methods annotated with @Async or specific service methods
    @Before("execution(* com.example.service.*.*(..))")
    public void logActiveThreads() {
        int activeCount = taskExecutor.getActiveCount();
        System.out.println("Active Threads on Method Call: " + activeCount);
    }

    @Scheduled (fixedRate = 30000)
    public void reportThreadPoolStatus() {
        ThreadPoolExecutor executor = taskExecutor.getThreadPoolExecutor();
       log.info("Active Threads (30s interval): {} | Queue Size : {} ",executor.getActiveCount(),executor.getQueue().size());
    }
}