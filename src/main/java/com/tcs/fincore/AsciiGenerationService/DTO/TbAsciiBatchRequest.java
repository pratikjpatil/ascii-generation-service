package com.tcs.fincore.AsciiGenerationService.DTO;

import java.io.Serializable;
import java.util.List;

public class TbAsciiBatchRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<String> reportIds;   // Changed to List: ["NWSA", "YSA"]
    private List<String> branchCodes; // Changed to List: ["00301", "00691", "00001"]
    private String balanceDate;       // "2025-10-01"
    private String outputDir;         // "E:/Reports/TB_ASCII"

    // Getters and Setters
    public List<String> getReportIds() { return reportIds; }
    public void setReportIds(List<String> reportIds) { this.reportIds = reportIds; }

    public List<String> getBranchCodes() { return branchCodes; }
    public void setBranchCodes(List<String> branchCodes) { this.branchCodes = branchCodes; }

    public String getBalanceDate() { return balanceDate; }
    public void setBalanceDate(String balanceDate) { this.balanceDate = balanceDate; }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
}