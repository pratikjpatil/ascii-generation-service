package com.tcs.fincore.AsciiGenerationService.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcs.fincore.AsciiGenerationService.dto.ReportGenerationDTO;
import com.tcs.fincore.AsciiGenerationService.dto.ReportGenerationResponseDTO;
import com.tcs.fincore.AsciiGenerationService.dto.TbAsciiBatchRequestDto;
import com.tcs.fincore.AsciiGenerationService.exception.BatchInitiationException;
import com.tcs.fincore.AsciiGenerationService.exception.ConfigNotFoundException;
import com.tcs.fincore.AsciiGenerationService.exception.InvalidReportRequestException;
import com.tcs.fincore.AsciiGenerationService.kafka.ReportKafkaProducer;
import com.tcs.fincore.AsciiGenerationService.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaService {
    private static final String TYPE_TB = "TB";
    private static final String TYPE_NORMAL = "NORMAL";

    private final AsciiGenerationService asciiService;
    private final TbAsciiService tbAsciiService;
    private final ReportKafkaProducer producer;
    private final ObjectMapper objectMapper;

    public void processReport(ReportGenerationDTO request) {
        ReportGenerationResponseDTO event = buildEvent(request);
        try {
            if (isTb(request)) {
                TbAsciiBatchRequestDto tbRequest = objectMapper.convertValue(request, TbAsciiBatchRequestDto.class);
                tbAsciiService.generateFiles(tbRequest, "KAFKA-Scheduled", event);
                return;
            }
            if (isNormal(request)) {
                NormalPayload payload = readNormalPayload(request);
                asciiService.initiateBatch(Long.valueOf(payload.id()), payload.reportDate(), event);
                return;
            }
            throw new InvalidReportRequestException("Unsupported reportType: " + request.getReportType());
        } catch (NumberFormatException ex) {
            publishFailure(event, "Invalid numeric config id");
        } catch (InvalidReportRequestException | ConfigNotFoundException | BatchInitiationException ex) {
            publishFailure(event, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Kafka report generation failed. processId={}, stageId={}, runId={}",
                    request == null ? null : request.getProcessId(), request == null ? null : request.getStageId(), request == null ? null : request.getRunId(), ex);
            publishFailure(event, ex.getMessage());
        }
    }

    private boolean isTb(ReportGenerationDTO request) {
        return request != null && TYPE_TB.equalsIgnoreCase(request.getReportType());
    }

    private boolean isNormal(ReportGenerationDTO request) {
        return request != null && (TYPE_NORMAL.equalsIgnoreCase(request.getReportType())
                || "NORMAL_ASCII".equalsIgnoreCase(request.getReportType()));
    }

    private NormalPayload readNormalPayload(ReportGenerationDTO request) {
        NormalPayload payload = objectMapper.convertValue(request.getPayload(), NormalPayload.class);
        if (payload.id() == null || payload.id().isBlank()) {
            throw new InvalidReportRequestException("Config id is missing");
        }
        if (payload.reportDate() == null || payload.reportDate().isBlank()) {
            throw new InvalidReportRequestException("Report date is missing");
        }
        return payload;
    }

    private ReportGenerationResponseDTO buildEvent(ReportGenerationDTO request) {
        ReportGenerationResponseDTO event = new ReportGenerationResponseDTO();
        if (request != null) {
            event.setProcessId(request.getProcessId());
            event.setStageId(request.getStageId());
            event.setRunId(request.getRunId());
            event.setReportType(request.getReportType());
            event.setPayload(request.getPayload());
        }
        event.setReportTriggered(Boolean.TRUE);
        event.setStartTime(Instant.now().toString());
        return event;
    }

    private void publishFailure(ReportGenerationResponseDTO event, String message) {
        event.setStatus(Constants.FAILED);
        event.setRemark(message);
        event.setEndTime(Instant.now().toString());
        producer.sendResponse(event);
    }

    private record NormalPayload(String id, String reportDate) {
    }
}
