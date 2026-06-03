package com.tcs.fincore.AsciiGenerationService.dto;

import lombok.Data;

@Data
public class ReportRequest {
    private String id;
    private String reportDate; // The user sends this in JSON
}