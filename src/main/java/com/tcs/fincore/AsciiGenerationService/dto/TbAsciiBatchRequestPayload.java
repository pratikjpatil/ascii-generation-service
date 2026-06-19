package com.tcs.fincore.AsciiGenerationService.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern; // Import this
import lombok.Getter;

import java.util.List;

@Getter
public class TbAsciiBatchRequestPayload {

    @NotNull
    @Size(min = 1, max = 10, message = "Request must have at least one reportId or a maximum of 10!")
    private List<String> reportIds;

    @NotNull
    @Size(min = 1, max = 50000, message = "Request must have at least one branchCode or a maximum of 50000!")
    private List<String> branchCodes;

    @Pattern(
            regexp = "^\\d{4}-\\d{2}-\\d{2}$",
            message = "balanceDate must be in yyyy-MM-dd format (e.g., 2025-10-01)"
    )
    private String balanceDate;

    private String exportPath;
}