package com.tcs.fincore.AsciiGenerationService.DTO;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.tcs.fincore.AsciiGenerationService.util.FormatStatus;
import com.tcs.fincore.AsciiGenerationService.util.ReportFormat;
import lombok.Data;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
@Data
public class ReportGenerationResponseDTO {
    private String processRunId;
    private String stageId;
    private Integer runId;
    private String type;
    
    private String id;
    private String reportDate;

    private String status;
    private String remark;
    // Map<ReportFormat, FormatStatus> formatResults;
    // Map<ReportFormat, String> formatErrors;

    private Map<String, String> formatResults;
    private Map<String, String> formatErrors;
}