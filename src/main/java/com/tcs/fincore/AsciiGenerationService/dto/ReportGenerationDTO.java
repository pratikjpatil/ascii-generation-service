package com.tcs.fincore.AsciiGenerationService.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import tools.jackson.databind.JsonNode;

@Data
public class ReportGenerationDTO {

    @NotNull(message = "Process Id Cannot be null")
    @NotBlank(message = "Process Id Cannot be blank")
    private String processRunId;

    @NotNull(message = "Stage Id Cannot be null")
    @NotBlank(message = "Stage Id Cannot be Blank")
    private String stageId;

    @NotNull(message = "Run Id Cannot be null")
    private String runId;

    @NotNull(message = "Report Type Cannot be null")
    @NotBlank(message = "Report Type Cannot be blank")
    private String reportType;

    @NotNull(message = "Payload Cannot be null")
    private JsonNode payload;

}