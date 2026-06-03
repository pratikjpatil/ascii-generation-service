package com.tcs.fincore.AsciiGenerationService.service;
import com.tcs.fincore.AsciiGenerationService.dto.ReportGenerationDTO;
import com.tcs.fincore.AsciiGenerationService.dto.ReportGenerationResponseDTO;
import com.tcs.fincore.AsciiGenerationService.kafka.ReportKafkaProducer;
import com.tcs.fincore.AsciiGenerationService.util.Constants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaService {
    private final AsciiGenerationService asciiService;
    private final ReportKafkaProducer producer;
    public void processReport(ReportGenerationDTO request) {
        
        ReportGenerationResponseDTO response = new ReportGenerationResponseDTO();
        try {
            String idStr=request.getPayload().getId();
            
            String date=request.getPayload().getReportDate();
           
            Long id= Long.valueOf(idStr);
            String status = asciiService.initiateBatch(id,date);
   
            response.setRunId(request.getRunId());
            response.setProcessRunId(request.getProcessRunId());
            response.setStageId(request.getStageId());
            response.setType(request.getType());
            response.setId(idStr);
            response.setReportDate(date);

            response.setStatus(Constants.SUCCESS);
            response.setRemark(status != null ? status : "Batch started successfully" );

        } catch (Exception e ) {
            response.setRunId(request.getRunId());
            response.setProcessRunId(request.getProcessRunId());
            response.setStageId(request.getStageId());
            response.setType(request.getType());
            response.setId(request.getPayload().getId());
            response.setReportDate(request.getPayload().getReportDate());

            response.setStatus(Constants.FAILED);
            response.setRemark("Exception: " + e.getMessage());
       
        }
        producer.sendResponse(response);
    }
}