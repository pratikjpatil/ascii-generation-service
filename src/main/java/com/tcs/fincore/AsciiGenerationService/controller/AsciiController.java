package com.tcs.fincore.AsciiGenerationService.controller;

import com.tcs.fincore.AsciiGenerationService.dto.ReportGenerationResponseDTO;
import com.tcs.fincore.AsciiGenerationService.dto.ReportRequest;
import com.tcs.fincore.AsciiGenerationService.exception.BatchInitiationException;
import com.tcs.fincore.AsciiGenerationService.exception.ConfigNotFoundException;
import com.tcs.fincore.AsciiGenerationService.service.AsciiGenerationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/ascii")
public class AsciiController {

    @Autowired
    private AsciiGenerationService service;

    @PostMapping("/generate")
    public ResponseEntity<String> generate(@RequestBody ReportRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body("Error: request body is missing");
        }
        if (request.getId() == null || request.getId().isBlank()) {
            return ResponseEntity.badRequest().body("Error: 'Id' is missing");
        }
        if (request.getReportDate() == null || request.getReportDate().isBlank()) {
            return ResponseEntity.badRequest().body("Error: 'Date' is missing");
        }

        Long id;
        try {
            id = Long.valueOf(request.getId());
        } catch (NumberFormatException ex) {
            return ResponseEntity.badRequest().body("Error: 'Id' must be numeric");
        }

        try {
            String status = service.initiateBatch(id, request.getReportDate(), buildRestEventTemplate(request));
            return ResponseEntity.accepted().body("Success: " + status);
        } catch (ConfigNotFoundException ex) {
            return ResponseEntity.badRequest().body("Error: " + ex.getMessage());
        } catch (BatchInitiationException ex) {
            return ResponseEntity.internalServerError().body("Error: " + ex.getMessage());
        }
    }

    @GetMapping("/status/{batchId}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String batchId) {
        Map<String, Object> status = service.getBatchStatus(batchId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    private ReportGenerationResponseDTO buildRestEventTemplate(ReportRequest request) {
        ReportGenerationResponseDTO event = new ReportGenerationResponseDTO();
        event.setType("NORMAL_ASCII");
        event.setId(request.getId());
        event.setReportDate(request.getReportDate());
        return event;
    }
}