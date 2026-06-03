package com.tcs.fincore.AsciiGenerationService.kafka;

import com.tcs.fincore.AsciiGenerationService.dto.ReportGenerationDTO;
import com.tcs.fincore.AsciiGenerationService.dto.ReportGenerationResponseDTO;
import com.tcs.fincore.AsciiGenerationService.service.KafkaService;
import com.tcs.fincore.AsciiGenerationService.util.Constants;

//import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

// @Slf4j
// @Service
// @RequiredArgsConstructor
// @Validated
// public class ReportKafkaConsumer {
//
//     private final KafkaService kafkaService;
//     private final ReportKafkaProducer producer;
//
//     @KafkaListener(topics = "report-generation-request", groupId = "Airflow_ETL", properties = {
//             "spring.json.value.default.type=com.fincore.ReportService.dto.ReportGenerationDTO" })
//     public void consume(@Valid ReportGenerationDTO request) {
//
//         try {
//             log.info("Report Generation Request Recieved:{} ", request);
//             kafkaService.processReport(request);
//
//         }
//         catch (CallNotPermittedException e) {
//             log.error("Circuit Breaker is OPEN. Skipping/Retrying later for RunId: {}", request.getRunId());
//
//         }
//
//         catch (ConstraintViolationException e) {
//             log.info("Constraint Violation {}",e.getMessage());
//             ReportGenerationResponseDTO response = new ReportGenerationResponseDTO();
//             response.setRunId(request.getRunId());
//             response.setProcessRunId(request.getProcessRunId());
//             response.setStageId(request.getStageId());
//             response.setStatus(Constants.FAILED);
//             response.setRemark("Validation Failed " + e.getMessage());
//             producer.sendResponse(response);
//         }
//
//     }
// }
 
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportKafkaConsumer {

    private final KafkaService kafkaService;
    private final ReportKafkaProducer producer;
    private final Validator validator;


    @KafkaListener(topics = "ascii-report-generation-request", groupId = "Airflow_ETL",
    properties = {
            "spring.json.value.default.type=com.tcs.fincore.AsciiGenerationService.dto.ReportGenerationDTO"}
    )
    public void consume(@Valid @Payload ReportGenerationDTO request) {
        log.info("Received ASCII Report Generation Request: {}", request);
        log.info("Payload object: {}",request.getPayload());
        ReportGenerationResponseDTO response = new ReportGenerationResponseDTO();
        try {
            Set<ConstraintViolation<ReportGenerationDTO>> violations = validator.validate(request);
            if (!violations.isEmpty()) {
                String errorMessage = violations.stream()
                        .map(ConstraintViolation::getMessage)
                        .collect(Collectors.joining(", "));
                log.error("Validation Failed: {}", errorMessage);
                response.setRunId(request.getRunId());
                response.setProcessRunId(request.getProcessRunId());
                response.setStageId(request.getStageId());
                response.setStatus(Constants.FAILED);
                response.setRemark("Validation Failed: " + errorMessage);


               producer.sendResponse(response);
                return;
            }
            kafkaService.processReport(request);
        } catch (RuntimeException e) {
            log.error("Processing Failed for RunId {}: {}", request.getRunId(), e.getMessage(), e);
            response.setRunId(request.getRunId());
            response.setProcessRunId(request.getProcessRunId());
            response.setStageId(request.getStageId());
            response.setStatus(Constants.FAILED);
            response.setRemark("Processing Failed: " + e.getMessage());

           producer.sendResponse(response);
        }
    }
}