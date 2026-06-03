package com.tcs.fincore.AsciiGenerationService.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NonNull;

@Getter
public class TbAsciiBatchRequestDto {

    @NonNull
    @Size(max=40,message = "Max 10 characters allow!")
    private String processRunId;

    @NonNull
    @Size(max=40,message = "Max 10 characters allow!")
    private String runId;

    @NonNull
    @Size(max=40,message = "Max 10 characters allow!")
    private String stageId;

    @NotNull
    @Size(max=20,message = "Max 20 characters allow!")
    private String type;

    @Valid
    private TbAsciiBatchRequestPayload payload;
}