package com.tcs.fincore.AsciiGenerationService.dto;


import org.apache.hadoop.fs.Path;

public class FileJob {
    private Long configId;
    private Path filePath;
    private String batchId; // <--- This was missing
    private String date;

    // The constructor must accept 3 arguments now
    public FileJob(Long configId, Path filePath, String batchId, String date) {
        this.configId = configId;
        this.filePath = filePath;
        this.batchId = batchId;
        this.date=date;
    }

    public Long getConfigId() { return configId; }
    public Path getFilePath() { return filePath; }
    public String getBatchId() { return batchId; } // <--- This getter was missing
    public String getDate(){ return date; }
}