package com.tcs.fincore.AsciiGenerationService.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TbAsciiBatchRequestDto {

    @NotNull
    @Size(max=40,message = "Max 10 characters allow!")
    private String processRunId;

    @NotNull
    @Size(max=40,message = "Max 10 characters allow!")
    private String runId;

    @NotNull
    @Size(max=40,message = "Max 10 characters allow!")
    private String stageId;

    @NotNull
    @Size(max=20,message = "Max 20 characters allow!")
    private String reportType;

    @Valid
    private TbAsciiBatchRequestPayload payload;
}