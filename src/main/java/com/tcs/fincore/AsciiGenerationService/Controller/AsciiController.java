package com.tcs.fincore.AsciiGenerationService.Controller;

import com.tcs.fincore.AsciiGenerationService.DTO.ReportRequest;
import com.tcs.fincore.AsciiGenerationService.Service.AsciiGenerationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ascii")
public class AsciiController {

    @Autowired
    private AsciiGenerationService service;

    @PostMapping("/generate")

    public ResponseEntity<String> generate(@RequestBody ReportRequest request) {
        try {
            if (request.getId() == null 
                || request.getId().isBlank())
            {
                return ResponseEntity.badRequest().body("Error: 'Id' is missing");
            }

             if (request.getReportDate() == null || request.getReportDate().isBlank()) {
                return ResponseEntity.badRequest().body("Error: 'Date' is missing");
            }
            Long id=Long.valueOf(request.getId());
            String status = service.initiateBatch(id,request.getReportDate());

            return ResponseEntity.accepted().body("Success: " + status);

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }
}