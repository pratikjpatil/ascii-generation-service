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
            if (request.getId() == null) {
                return ResponseEntity.badRequest().body("Error: 'id' is missing");
            }

            String status = service.initiateBatch(request.getId());

            return ResponseEntity.accepted().body("Success: " + status);

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }
}