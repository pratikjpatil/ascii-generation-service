package com.tcs.fincore.AsciiGenerationService.kafka;

import com.tcs.fincore.AsciiGenerationService.dto.ReportGenerationResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
//import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportKafkaProducer {

//    private final KafkaTemplate<String, ReportGenerationResponseDTO> kafkaTemplate;

    private static final String RESPONSE_TOPIC = "ascii-report-generation-response";

    public void sendResponse(ReportGenerationResponseDTO response) {
         log.info("ASCII Report Generation Response Sent :{} ",response);

        // kafkaTemplate.send(RESPONSE_TOPIC, String.valueOf(response.getRunId()),response);
//        kafkaTemplate.send(RESPONSE_TOPIC, response);
    }
}