package com.tcs.fincore.AsciiGenerationService.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReportGenerationResponseDTO {
    @JsonAlias({"processRunId"})
    private String processId;
    private String stageId;
    private String runId;
    @JsonAlias({"type"})
    private String reportType;
    private Object payload;
    private Boolean reportTriggered;
    private String startTime;
    private String endTime;
    private String status;
    private String remark;

    private String id;
    private String reportDate;
    private Map<String, String> formatResults;
    private Map<String, String> formatErrors;

    public String getProcessRunId() {
        return processId;
    }

    public void setProcessRunId(String processRunId) {
        this.processId = processRunId;
    }

    public String getType() {
        return reportType;
    }

    public void setType(String type) {
        this.reportType = type;
    }
}
