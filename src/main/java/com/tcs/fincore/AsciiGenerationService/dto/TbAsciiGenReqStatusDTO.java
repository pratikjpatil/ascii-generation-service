package com.tcs.fincore.AsciiGenerationService.dto;

import com.tcs.fincore.AsciiGenerationService.util.ReportStatus;
import lombok.Getter;

import java.util.Map;

@Getter
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

            if (status.equals(ReportStatus.QUEUED)) {
                if (this.status == null || this.status.equals("")) {
                    this.status = status.name();
                }
                return;
            }
            this.status=this.status = status.name();
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public void setMetrics(Map<String, Map<String, Object>> metrics) {
        this.metrics = metrics;
    }

    public void setMessage(String message) {
        this.message = message;
    }

}