package com.tcs.fincore.AsciiGenerationService.DTO;

import java.nio.file.Path;

public class FileJob {
    private Long configId;
    private Path filePath;
    private String batchId; // <--- This was missing

    // The constructor must accept 3 arguments now
    public FileJob(Long configId, Path filePath, String batchId) {
        this.configId = configId;
        this.filePath = filePath;
        this.batchId = batchId;
    }

    public Long getConfigId() { return configId; }
    public Path getFilePath() { return filePath; }
    public String getBatchId() { return batchId; } // <--- This getter was missing
}