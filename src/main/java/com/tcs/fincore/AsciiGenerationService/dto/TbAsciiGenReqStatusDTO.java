package com.tcs.fincore.AsciiGenerationService.dto;

import com.tcs.fincore.AsciiGenerationService.util.ReportStatus;
import lombok.Data;
import lombok.Getter;

import java.util.Map;

@Data
public class TbAsciiGenReqStatusDTO {
    private final String processRunId;
    private final String stageId;
    private final String runId;
    private final String type;
    private final String creationMethod;
    private String status;
    private String message;
    private String startTime;
    private String endTime;
    private Map<String, Map<String, Object>> metrics;

    public TbAsciiGenReqStatusDTO(String processRunId, String stageId, String runId, String type, String creationMethod) {
        this.processRunId = processRunId;
        this.stageId = stageId;
        this.runId = runId;
        this.type = type;
        this.creationMethod = creationMethod;
    }

    public synchronized void setStatus(ReportStatus status) {
        if (ReportStatus.QUEUED.equals(status) && this.status != null && !this.status.isBlank()) {
            return;
        }
        this.status = status.name();
    }

    public String compositeRunId() {
        return processRunId + "_" + stageId + "_" + runId;
    }

}