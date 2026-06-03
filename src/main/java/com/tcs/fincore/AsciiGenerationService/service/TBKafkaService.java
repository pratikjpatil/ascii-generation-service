package com.tcs.fincore.AsciiGenerationService.service;

import com.tcs.fincore.AsciiGenerationService.dto.TbAsciiBatchRequestPayload;
import com.tcs.fincore.AsciiGenerationService.dto.TbAsciiBatchRequestDto;
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
    @Autowired
    TbAsciiService tbAsciiService;

    @Autowired
    KafkaTemplate kafkaTemplate;

    @KafkaListener(topics = "tb_ascii-report-generation-request", groupId = "Airflow_ETL",
            properties = {
                    "spring.json.value.default.type:com.tcs.fincore.AsciiGenerationService.dto.TbAsciiBatchRequestDto"}
    )
    public void triggerTbAsciiGeneration(TbAsciiBatchRequestDto req)
    {
         TbAsciiBatchRequestPayload request=req.getPayload();
        if (request.getReportIds() == null || request.getReportIds().isEmpty() ||
                request.getBranchCodes() == null || request.getBranchCodes().isEmpty() ||
                request.getBalanceDate() == null) {
                TbAsciiGenReqStatusDTO status= new TbAsciiGenReqStatusDTO(req.getProcessRunId(),req.getStageId(), req.getRunId(), req.getType(),"KAFKA-Scheduled");
                status.setStatus(ReportStatus.FAILED);
                sendEvent("tb_ascii-report-generation-request-status",status);
        }
         SingletonMap<ReportStatus,String> submittionState = tbAsciiService.generateFiles(req,"KAFKA-scheduled");
    }

    @Bean
    public KafkaListenerErrorHandler customKafkaListenerErrorHandler() {
        return (message, exception) -> {
            System.err.println("Error: " + exception.getMessage());
            return null;
        };
    }

    public void sendEvent(String topic, Object payload){
        kafkaTemplate.send(topic,payload).whenComplete((res,err)->{
            if(res!=null){
                log.error(res.toString());
            }
        });
    }
}