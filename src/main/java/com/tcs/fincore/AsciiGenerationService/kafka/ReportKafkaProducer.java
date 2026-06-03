package com.tcs.fincore.AsciiGenerationService.kafka;

import com.tcs.fincore.AsciiGenerationService.dto.ReportGenerationResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportKafkaProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private static final String RESPONSE_TOPIC = "ascii-report-generation-response";

    public void sendResponse(ReportGenerationResponseDTO response) {
        kafkaTemplate.send(RESPONSE_TOPIC, String.valueOf(response.getRunId()), response)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        log.error("Failed to publish ASCII report generation response. topic={}, runId={}, status={}",
                                RESPONSE_TOPIC, response.getRunId(), response.getStatus(), error);
                    } else {
                        log.info("Published ASCII report generation response. topic={}, runId={}, status={}",
                                RESPONSE_TOPIC, response.getRunId(), response.getStatus());
                    }
                });
    }
}
