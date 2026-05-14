package com.tcs.fincore.AsciiGenerationService.Controller;

import com.tcs.fincore.AsciiGenerationService.DTO.TbAsciiBatchRequest;
import com.tcs.fincore.AsciiGenerationService.Service.TbAsciiService;
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
//        log.info("Batch Request: {} Reports x {} Branches", request.getReportIds().size(), request.getBranchCodes().size());
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
    public ResponseEntity<Map<String, Object>> generateBatch(@RequestBody TbAsciiBatchRequest request) {

      // 1. Basic Validation
      if (request.getReportIds() == null || request.getReportIds().isEmpty() ||
              request.getBranchCodes() == null || request.getBranchCodes().isEmpty() ||
              request.getBalanceDate() == null) {
          return ResponseEntity.badRequest().body(Map.of("error", "Mandatory fields missing (reportIds, branchCodes, balanceDate)"));
      }

      log.info("Batch Request: {} Reports x {} Branches", request.getReportIds().size(), request.getBranchCodes().size());
       boolean isSubmitted = tbAsciiService.generateFiles(
                      request.getReportIds(),
                      request.getBranchCodes(),
                      request.getBalanceDate(),
                      request.getOutputDir()
              );
        Map<String, Object> response = new HashMap<>();
        if(isSubmitted) {
	        response.put("status", "ACCEPTED");
	        response.put("message", "Batch generation started in background.");
	        response.put("tasks_queued", request.getReportIds().size() * request.getBranchCodes().size());
	        return ResponseEntity.accepted().body(response);
        }else {
		    response.put("status", "REJECTED");
	        response.put("message", "Batch generation faled in background due to already queued requests.");
	        return ResponseEntity.status(429).body(response);
        }
    }
    
}