package com.tcs.fincore.AsciiGenerationService.Service;


import com.tcs.fincore.AsciiGenerationService.DTO.FileJob;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy; // <--- IMPORT THIS
import org.springframework.stereotype.Service;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

@Service
@Slf4j
public class JobQueueManager {

    private final AsciiGenerationService asciiService;

    // Use Constructor Injection with @Lazy to break the cycle
    @Autowired
    public JobQueueManager(@Lazy AsciiGenerationService asciiService) {
        this.asciiService = asciiService;
    }

    // The Queue: Holds 'FileJob' (config ID + Specific File Path)
    private final BlockingQueue<FileJob> jobQueue = new LinkedBlockingQueue<>();

    // 10 Threads processing files in parallel
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    private volatile boolean running = true;

    @PostConstruct
    public void startWorkers() {
        Thread dispatcher = new Thread(() -> {
            while (running) {
                try {
                    FileJob job = jobQueue.take();
                    executorService.submit(() -> asciiService.processSingleFile(job));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        dispatcher.setName("Job-Dispatcher");
        dispatcher.start();
        log.info("Batch Processor Started. Waiting for jobs...");
    }

    public void submitFileJob(FileJob job) {
        jobQueue.offer(job);
    }

    public int getQueueSize() {
        return jobQueue.size();
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        executorService.shutdown();
    }
}