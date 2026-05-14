package com.tcs.fincore.AsciiGenerationService.DTO;


import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import lombok.Data;

@Data
public class ReportGenerationDTO {

    @NotNull(message = "Process Run Id Cannot be null")
    private String processRunId;

    @NotNull(message = "Stage Id Cannot be null")
    @NotBlank(message = "Stage Id Cannot be Blank")
    private String stageId;

    @NotNull(message = "Run Id Cannot be null")
    private Integer runId;


    @NotNull(message = "Report Type Cannot be null")
    @NotBlank(message = "Report Type Cannot be blank")
    private String type;
    
    @Valid
    @NotNull
    private Payload payload;
    @Data
    public static class Payload
    {
        @NotNull(message = "Id cannot be null")
        private String id;

        @NotNull(message = "Report Date cannot be blank")
        private String reportDate;
    }

}