package com.tcs.fincore.AsciiGenerationService.kafka;

import com.tcs.fincore.AsciiGenerationService.dto.ReportGenerationResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportKafkaProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.kafka.report.status-topic:ascii-report-generation-status}")
    private String statusTopic;

    public void sendResponse(ReportGenerationResponseDTO response) {
        kafkaTemplate.send(statusTopic, eventKey(response), response).whenComplete((result, error) -> {
            if (error != null) {
                log.error("Failed to publish report generation status. topic={}, key={}, status={}",
                        statusTopic, eventKey(response), response.getStatus(), error);
            } else {
                log.info("Published report generation status. topic={}, key={}, status={}",
                        statusTopic, eventKey(response), response.getStatus());
            }
        });
    }

    private String eventKey(ReportGenerationResponseDTO response) {
        if (response == null) {
            return null;
        }
        return String.join(":", nullToEmpty(response.getProcessId()), nullToEmpty(response.getStageId()), nullToEmpty(response.getRunId()));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
