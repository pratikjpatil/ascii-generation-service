package com.tcs.fincore.AsciiGenerationService.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
public class NormalAsciiPayload {
    @NotNull(message = "ID cannot be null")
    @NotBlank(message = "ID cannot be blank")
    private String id;

    @NotNull(message = "Report Date cannot be null")
    @NotBlank(message = "Report Date cannot be blank")
    private String reportDate;

}