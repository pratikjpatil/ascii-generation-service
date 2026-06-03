package com.tcs.fincore.AsciiGenerationService.dto;
import lombok.Data;

import java.util.Map;
@Data
public class ReportGenerationResponseDTO {
    private String processRunId;
    private String stageId;
    private Integer runId;
    private String type;
    
    private String id;
    private String reportDate;

    private String status;
    private String remark;
    // Map<ReportFormat, FormatStatus> formatResults;
    // Map<ReportFormat, String> formatErrors;

    private Map<String, String> formatResults;
    private Map<String, String> formatErrors;
}