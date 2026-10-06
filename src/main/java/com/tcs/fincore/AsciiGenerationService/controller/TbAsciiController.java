package com.tcs.fincore.AsciiGenerationService.controller;

import com.tcs.fincore.AsciiGenerationService.dto.TbAsciiBatchRequestDto;
import com.tcs.fincore.AsciiGenerationService.dto.TbAsciiBatchRequestPayload;
import com.tcs.fincore.AsciiGenerationService.dto.TbAsciiGenReqStatusDTO;
import com.tcs.fincore.AsciiGenerationService.service.TbAsciiService;
import com.tcs.fincore.AsciiGenerationService.util.ReportStatus;
import org.apache.commons.collections4.map.SingletonMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ascii/tb")
public class TbAsciiController {

    @Autowired
    private TbAsciiService tbAsciiService;

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateBatch(@RequestBody TbAsciiBatchRequestDto requestNew) {
        if (requestNew == null || requestNew.getPayload() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Request payload is missing"));
        }
        TbAsciiBatchRequestPayload request = requestNew.getPayload();
        if (request.getReportIds() == null || request.getReportIds().isEmpty() ||
                request.getBranchCodes() == null || request.getBranchCodes().isEmpty() ||
                request.getBalanceDate() == null || request.getBalanceDate().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Mandatory fields missing (reportIds, branchCodes, balanceDate)"));
        }

        SingletonMap<ReportStatus, String> submissionState = tbAsciiService.generateFiles(requestNew, "REST");
        Map<String, Object> response = new HashMap<>();
        response.put("status", submissionState.getKey().name());
        response.put("message", submissionState.getValue());
        response.put("run_id", requestNew.getProcessRunId() + "_" + requestNew.getStageId() + "_" + requestNew.getRunId());

        int statusCode = switch (submissionState.getKey()) {
            case REJECTED -> 429;
            case QUEUED -> {
                response.put("tasks_queued", request.getReportIds().size() * request.getBranchCodes().size());
                yield 202;
            }
            case GENERATING, PARTIALLY_FAILED, SUCCESS -> 200;
            case FAILED -> 400;
        };
        return ResponseEntity.status(statusCode).body(response);
    }

    @PostMapping("/status/{run_id}")
    public ResponseEntity<TbAsciiGenReqStatusDTO> getStatus(@PathVariable String run_id) {
        TbAsciiGenReqStatusDTO status = tbAsciiService.getStatus(run_id);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }
}