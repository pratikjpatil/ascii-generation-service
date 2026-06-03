package com.tcs.fincore.AsciiGenerationService.service;

import com.tcs.fincore.AsciiGenerationService.dto.ReportGenerationDTO;
import com.tcs.fincore.AsciiGenerationService.dto.ReportGenerationResponseDTO;
import com.tcs.fincore.AsciiGenerationService.exception.BatchInitiationException;
import com.tcs.fincore.AsciiGenerationService.exception.ConfigNotFoundException;
import com.tcs.fincore.AsciiGenerationService.exception.InvalidReportRequestException;
import com.tcs.fincore.AsciiGenerationService.kafka.ReportKafkaProducer;
import com.tcs.fincore.AsciiGenerationService.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaService {
    private final AsciiGenerationService asciiService;
    private final ReportKafkaProducer producer;

    public void processReport(ReportGenerationDTO request) {
        ReportGenerationResponseDTO response = buildResponseTemplate(request);
        try {
            validateRequest(request);
            Long id = Long.valueOf(request.getPayload().getId());
            asciiService.initiateBatch(id, request.getPayload().getReportDate(), response);
        } catch (NumberFormatException ex) {
            log.error("Invalid ASCII config id received from Kafka. id={}", request.getPayload() == null ? null : request.getPayload().getId(), ex);
            response.setStatus(Constants.FAILED);
            response.setRemark("Invalid numeric config id: " + safePayloadId(request));
            producer.sendResponse(response);
        } catch (InvalidReportRequestException | ConfigNotFoundException | BatchInitiationException ex) {
            log.error("ASCII generation request failed. processRunId={}, stageId={}, runId={}",
                    request.getProcessRunId(), request.getStageId(), request.getRunId(), ex);
            response.setStatus(Constants.FAILED);
            response.setRemark(ex.getMessage());
            producer.sendResponse(response);
        }
    }

    private void validateRequest(ReportGenerationDTO request) {
        if (request == null || request.getPayload() == null) {
            throw new InvalidReportRequestException("Request payload is missing");
        }
        if (request.getPayload().getId() == null || request.getPayload().getId().isBlank()) {
            throw new InvalidReportRequestException("Config id is missing");
        }
        if (request.getPayload().getReportDate() == null || request.getPayload().getReportDate().isBlank()) {
            throw new InvalidReportRequestException("Report date is missing");
        }
    }

    private ReportGenerationResponseDTO buildResponseTemplate(ReportGenerationDTO request) {
        ReportGenerationResponseDTO response = new ReportGenerationResponseDTO();
        if (request != null) {
            response.setRunId(request.getRunId());
            response.setProcessRunId(request.getProcessRunId());
            response.setStageId(request.getStageId());
            response.setType(request.getType());
            if (request.getPayload() != null) {
                response.setId(request.getPayload().getId());
                response.setReportDate(request.getPayload().getReportDate());
            }
        }
        return response;
    }

    private String safePayloadId(ReportGenerationDTO request) {
        return request == null || request.getPayload() == null ? null : request.getPayload().getId();
    }
}
