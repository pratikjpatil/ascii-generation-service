package com.tcs.fincore.AsciiGenerationService.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReportGenerationDTO {

    @JsonAlias({"processRunId"})
    @NotNull(message = "Process Id Cannot be null")
    @NotBlank(message = "Process Id Cannot be blank")
    private String processId;

    @NotNull(message = "Stage Id Cannot be null")
    @NotBlank(message = "Stage Id Cannot be Blank")
    private String stageId;

    @NotNull(message = "Run Id Cannot be null")
    private String runId;

    @JsonAlias({"type"})
    @NotNull(message = "Report Type Cannot be null")
    @NotBlank(message = "Report Type Cannot be blank")
    private String reportType;

    @NotNull(message = "Payload Cannot be null")
    private JsonNode payload;

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
