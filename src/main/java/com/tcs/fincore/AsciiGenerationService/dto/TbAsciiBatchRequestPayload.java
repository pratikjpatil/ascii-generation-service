package com.tcs.fincore.AsciiGenerationService.dto;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;

import java.util.List;

@Getter
public class TbAsciiBatchRequestPayload{
    @NotNull
    @Size(min=1,max=10,message = "Request must have atleast one reportIds or can have maximum of 10!")
    private List<String> reportIds;   // Changed to List: ["NWSA", "YSA"]

    @NotNull
    @Size(min=1,max=50000,message = "Request must have atleast one reportIds or can have maximum of 50000!")
    private List<String> branchCodes; // Changed to List: ["00301", "00691", "00001"]

    private String balanceDate;       // "2025-10-01"
//    private String outputDir;         // "E:/Reports/TB_ASCII"

}