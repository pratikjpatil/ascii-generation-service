package com.tcs.fincore.AsciiGenerationService.controller;

import com.tcs.fincore.AsciiGenerationService.dto.TbAsciiBatchRequestDto;
import com.tcs.fincore.AsciiGenerationService.dto.TbAsciiBatchRequestPayload;
import com.tcs.fincore.AsciiGenerationService.service.TbAsciiService;
import com.tcs.fincore.AsciiGenerationService.util.ReportStatus;
import org.apache.commons.collections4.map.SingletonMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ascii/tb")
public class TbAsciiController {

    private static final Logger log = LoggerFactory.getLogger(TbAsciiController.class);

    @Autowired
    private TbAsciiService tbAsciiService;

//    @PostMapping("/generate")
//    public ResponseEntity<Map<String, Object>> generateBatch(@RequestBody TbAsciiBatchRequest request) {
//
//        // 1. Basic Validation
//        if (request.getReportIds() == null || request.getReportIds().isEmpty() ||
//                request.getBranchCodes() == null || request.getBranchCodes().isEmpty() ||
//                request.getBalanceDate() == null) {
//            return ResponseEntity.badRequest().body(Map.of("error", "Mandatory fields missing (reportIds, branchCodes, balanceDate)"));
//        }
//
//        //log.info("Batch Request: {} Reports x {} Branches", request.getReportIds().size(), request.getBranchCodes().size());
//
//        // 2. Loop and Dispatch Async Tasks
//        int taskCount = 0;
//        for (String reportId : request.getReportIds()) {
//            for (String branchCode : request.getBranchCodes()) {
//
//                // Fire and Forget
//                tbAsciiService.generateSingleFileAsync(
//                        reportId,
//                        branchCode,
//                        request.getBalanceDate(),
//                        request.getOutputDir()
//                );
//
//                taskCount++;
//            }
//        }
//
//        // 3. Return Immediate Response
//        Map<String, Object> response = new HashMap<>();
//        response.put("status", "ACCEPTED");
//        response.put("message", "Batch generation started in background.");
//        response.put("tasks_queued", taskCount);
//
//        return ResponseEntity.accepted().body(response);
//    }

    /*======================================================================================*/
    /*
        method used to process the request report generation batch in async way.
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateBatch(@RequestBody TbAsciiBatchRequestDto requestNew) {
        TbAsciiBatchRequestPayload request = requestNew.getPayload();
        // 1. Basic Validation
        if (request.getReportIds() == null || request.getReportIds().isEmpty() ||
                request.getBranchCodes() == null || request.getBranchCodes().isEmpty() ||
                request.getBalanceDate() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Mandatory fields missing (reportIds, branchCodes, balanceDate)"));
        }

        //log.info("Batch Request: {} Reports x {} Branches", request.getReportIds().size(), request.getBranchCodes().size());
        SingletonMap<ReportStatus, String> submissionState = tbAsciiService.generateFiles(
                requestNew,
                "REST-Manual"
        );
        Map<String, Object> response = new HashMap<>();
        response.put("status", submissionState.getKey().name());
        response.put("message", submissionState.getValue());
        int statusCode = switch (submissionState.getKey()) {
            case REJECTED -> {
                yield 429;
            }
            case QUEUED -> {
                response.put("tasks_queued", request.getReportIds().size() * request.getBranchCodes().size());
                yield 200;
            }
            case GENERATING, PARTIALLY_FAILED, SUCCESS -> 200;
            case FAILED -> {
                yield 400;
            }
        };
//            return ResponseEntity.accepted().body(response);
        return ResponseEntity.status(statusCode).body(response);

    }


    /*======================================================================================*/
    /*
        method used to get status of batch processing
     */
    @PostMapping("/status/{run_id}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String run_id) {
        Map<String, Object> body = new HashMap<>();
        return ResponseEntity.ofNullable(body);
    }

}