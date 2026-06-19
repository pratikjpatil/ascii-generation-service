package com.tcs.fincore.AsciiGenerationService.service;

import com.tcs.fincore.AsciiGenerationService.dto.TbAsciiBatchRequestDto;
import com.tcs.fincore.AsciiGenerationService.dto.TbAsciiBatchRequestPayload;
import com.tcs.fincore.AsciiGenerationService.dto.TbAsciiGenReqStatusDTO;
import com.tcs.fincore.AsciiGenerationService.util.ReportStatus;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.map.SingletonMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.KafkaListenerErrorHandler;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TBKafkaService {
    private static final String STATUS_TOPIC = "tb_ascii-report-generation-request-status";

    @Autowired
    TbAsciiService tbAsciiService;

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = "tb_ascii-report-generation-request", groupId = "Airflow_ETL",
            properties = {
                    "spring.json.value.default.type:com.tcs.fincore.AsciiGenerationService.dto.TbAsciiBatchRequestDto"}
    )
    public void triggerTbAsciiGeneration(TbAsciiBatchRequestDto req) {
        if (req == null || req.getPayload() == null || isInvalid(req.getPayload())) {
            TbAsciiGenReqStatusDTO status = new TbAsciiGenReqStatusDTO(
                    req == null ? null : req.getProcessRunId(),
                    req == null ? null : req.getStageId(),
                    req == null ? null : req.getRunId(),
                    req == null ? null : req.getType(),
                    "KAFKA-Scheduled"
            );
            status.setStatus(ReportStatus.FAILED);
            status.setMessage("Mandatory fields missing (reportIds, branchCodes, balanceDate)");
            sendEvent(STATUS_TOPIC, status);
            return;
        }
        SingletonMap<ReportStatus, String> submissionState = tbAsciiService.generateFiles(req, "KAFKA-Scheduled");
        log.info("TB ASCII Kafka request submitted. status={}, message={}", submissionState.getKey(), submissionState.getValue());
    }

    @Bean
    public KafkaListenerErrorHandler customKafkaListenerErrorHandler() {
        return (message, exception) -> {
            log.error("TB ASCII Kafka listener error. message={}", message, exception);
            return null;
        };
    }

    public void sendEvent(String topic, Object payload) {
        kafkaTemplate.send(topic, payload).whenComplete((res, err) -> {
            if (err != null) {
                log.error("Failed to publish TB ASCII status event. topic={}", topic, err);
            } else {
                log.info("Published TB ASCII status event. topic={}", topic);
            }
        });
    }

    private boolean isInvalid(TbAsciiBatchRequestPayload request) {
        return request.getReportIds() == null || request.getReportIds().isEmpty()
                || request.getBranchCodes() == null || request.getBranchCodes().isEmpty()
                || request.getBalanceDate() == null || request.getBalanceDate().isBlank();
    }
}