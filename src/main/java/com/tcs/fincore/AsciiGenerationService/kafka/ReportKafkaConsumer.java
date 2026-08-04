package com.tcs.fincore.AsciiGenerationService.kafka;

import com.tcs.fincore.AsciiGenerationService.dto.ReportGenerationDTO;
import com.tcs.fincore.AsciiGenerationService.dto.ReportGenerationResponseDTO;
import com.tcs.fincore.AsciiGenerationService.service.KafkaService;
import com.tcs.fincore.AsciiGenerationService.util.Constants;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportKafkaConsumer {

    private final KafkaService kafkaService;
    private final ReportKafkaProducer producer;
    private final Validator validator;

    @KafkaListener(topics = "${app.kafka.report.request-topic:ascii-report-generation-request}",
            groupId = "${spring.kafka.consumer.group-id:Airflow_ETL}",
            properties = "spring.json.value.default.type=com.tcs.fincore.AsciiGenerationService.dto.ReportGenerationDTO")
    public void consume(@Payload ReportGenerationDTO request) {
        log.info("Received report generation Kafka request. processId={}, stageId={}, runId={}, reportType={}",
                request == null ? null : request.getProcessId(), request == null ? null : request.getStageId(),
                request == null ? null : request.getRunId(), request == null ? null : request.getReportType());
        if (request == null) {
            producer.sendResponse(failedEvent(null, "Validation Failed: request body is missing"));
            return;
        }
        Set<ConstraintViolation<ReportGenerationDTO>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String errorMessage = violations.stream().map(ConstraintViolation::getMessage).collect(Collectors.joining(", "));
            log.error("Kafka report generation request validation failed: {}", errorMessage);
            producer.sendResponse(failedEvent(request, "Validation Failed: " + errorMessage));
            return;
        }
        kafkaService.processReport(request);
    }

    private ReportGenerationResponseDTO failedEvent(ReportGenerationDTO request, String remark) {
        ReportGenerationResponseDTO event = new ReportGenerationResponseDTO();
        if (request != null) {
            event.setProcessId(request.getProcessId());
            event.setStageId(request.getStageId());
            event.setRunId(request.getRunId());
            event.setReportType(request.getReportType());
            event.setPayload(request.getPayload());
        }
        event.setReportTriggered(Boolean.FALSE);
        event.setStatus(Constants.FAILED);
        event.setRemark(remark);
        event.setEndTime(Instant.now().toString());
        return event;
    }
}
