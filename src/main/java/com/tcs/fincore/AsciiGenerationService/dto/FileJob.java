package com.tcs.fincore.AsciiGenerationService.dto;


import lombok.Data;
import org.apache.hadoop.fs.Path;

@Data
public class FileJob {
    private Long configId;
    private Path filePath;
    private String batchId;
    private String date;

    // The constructor must accept 3 arguments now
    public FileJob(Long configId, Path filePath, String batchId, String date) {
        this.configId = configId;
        this.filePath = filePath;
        this.batchId = batchId;
        this.date=date;
    }

}