package com.tcs.fincore.AsciiGenerationService.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReportGenerationResponseDTO {

    private String processRunId;
    private String stageId;
    private String runId;
    private String reportType;
//    private Object payload;
    private Boolean reportTriggered;
    private String startTime;
    private String endTime;
    private String status;
    private String remarks;

    private String id;
    private String reportDate;
    private Map<String, String> formatResults;
    private Map<String, String> formatErrors;

}