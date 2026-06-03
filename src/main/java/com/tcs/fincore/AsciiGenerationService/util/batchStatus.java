package com.tcs.fincore.AsciiGenerationService.util;

import lombok.Data;

@Data
public class batchStatus {
    private int batchSize;
    private int batchCount;
    private int batchProcessed;
    private int errorCount=0;
}