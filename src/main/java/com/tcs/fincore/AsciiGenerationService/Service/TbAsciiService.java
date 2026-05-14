package com.tcs.fincore.AsciiGenerationService.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlOutParameter;
import org.springframework.jdbc.core.SqlParameter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
public class TbAsciiService {

    private static final Logger log = LoggerFactory.getLogger(TbAsciiService.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    AsyncTBService asyncTbService;
    
    @Autowired
    @Qualifier("tbTaskExecutor")
    ThreadPoolTaskExecutor executor;
    
    private final ExecutorService submissionSerice= 
//    		Executors.newSingleThreadExecutor();
    new ThreadPoolExecutor(
    	    1,
    	    1,
    	    0L,
    	    TimeUnit.SECONDS,
    	    new ArrayBlockingQueue<>(3),
    	    new ThreadPoolExecutor.AbortPolicy() // Throws RejectedExecutionException
    	);

    /**
     * ASYNC TASK: Generates ONE file.
     * The controller loops through the lists and calls this method for every combination.
     * Spring's ThreadPool (configured in TbAsyncConfig) manages the concurrency.
     */
    @Async("tbTaskExecutor")
    public void generateSingleFileAsync(String reportId, String branchCode, String dateStr, String outputDir) {

        // 1. Validate Date
        LocalDate date;
        try {
            date = LocalDate.parse(dateStr);
        } catch (Exception e) {
            log.error("[2TB] Invalid Date: {}", dateStr);
            return;
        }

        // 2. Secure File Path
        String safeDate = date.format(DateTimeFormatter.BASIC_ISO_DATE); // YYYYMMDD
        String fileName = String.format("%s_%s_%s.txt", reportId, branchCode, safeDate);

        Path targetPath;
        try {
            Path baseDir = Paths.get(outputDir).normalize();
            if (!Files.exists(baseDir)) Files.createDirectories(baseDir);

            targetPath = baseDir.resolve(fileName).normalize();
            if (!targetPath.startsWith(baseDir)) {
                throw new SecurityException("Path Traversal Detected");
            }
        } catch (Exception e) {
            log.error("[TB] Path Error for {}: {}", branchCode, e.getMessage());
            return;
        }

        log.info("[TB] Generating: {}", targetPath);


        // 3. Generate
        jdbcTemplate.execute((Connection conn) -> {
            try (CallableStatement cstmt = conn.prepareCall("{call SP_GENERATE_TB_ASCII_STREAM(?, ?, ?, ?)}")) {
                cstmt.setString(1, reportId);
                cstmt.setString(2, branchCode);
                cstmt.setDate(3, java.sql.Date.valueOf(date));
                cstmt.registerOutParameter(4, Types.REF_CURSOR);
                cstmt.execute();

                try (ResultSet rs = (ResultSet) cstmt.getObject(4);
                     BufferedWriter writer = Files.newBufferedWriter(targetPath, StandardCharsets.UTF_8)) {
                    while (rs.next()) {
                        String line = rs.getString(1);
                        if (line != null) {
                            writer.write(line);
                            writer.newLine();
                        }
                    }
                }
                log.info("[TB] DONE: {}", fileName);
            } catch (Exception e) {
                log.error("[TB] DB Error {}: {}", fileName, e.getMessage());
            }
            return null;
        });
    }
    
    public void generateTemplate(String reportId, String branchCode, String dateStr, String outputDir) {
    	
    }
    
//    private void processTasks(String runId, String headerId, String footerId, List<String> branchCode,String dateStr,String outPutDir ) {
//    	
//    }

    /*
      Submitter method to submit task in Report generator service
     */
    public boolean generateFiles(List<String> reportIds, List<String> branchCodes, String dateStr, String outputDir) {
    	//Non-Blocking background thread for submit ascii generation call
    	try {
            log.info("Submitting batch for report generation!");
    	    submissionSerice.submit(()->{
            long startTime = System.nanoTime();
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            String run_id= UUID.randomUUID().toString();
    		for(String reportId:reportIds)
            {
    		    SimpleJdbcCall jdbcCall = new SimpleJdbcCall(jdbcTemplate)
    			    .withProcedureName("SP_PARSE_TB_TEMPLATE_N1").declareParameters(
                            new SqlParameter("p_report_id", Types.VARCHAR),
                            new SqlParameter("p_run_id", Types.VARCHAR), // IN
                            new SqlOutParameter("p_header_id", Types.VARCHAR),
                            new SqlOutParameter("p_footer_id", Types.VARCHAR)
                    );
    		MapSqlParameterSource in = new MapSqlParameterSource().addValue("p_report_id", reportId).addValue("p_run_id", run_id);
            Map<String, Object> out= new HashMap<>();
            try {
                out = jdbcCall.execute(in);
            } catch (Exception e) {
                System.out.println(e.getMessage());
            }
            log.info("parsed template for the report: {}",reportId);
    		String p_header_id = (String) out.get("p_header_id");
    		String p_footer_id = (String) out.get("p_footer_id");
            log.info("Generating {} reports",reportId);
    		branchCodes.forEach((branchCode)->{
    			futures.add(
    					asyncTbService.performTask(reportId,run_id,branchCode,dateStr, p_header_id,p_footer_id,outputDir)
    					);
    		});
    		}
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            long endTime = System.nanoTime();
            long durationInMillis = (endTime - startTime) / 1_000_000;
            log.info("completed the batch with run_id : {}  in {} ms",run_id,durationInMillis);
            jdbcTemplate.update("delete from TB_BREAKUP_META_STAGE where run_id = ?", run_id);
    	});
    	}catch(RejectedExecutionException e) {
    		return false;
    	}
    	return true;
    }

    @PreDestroy
    public void closeConnection() {
      submissionSerice.shutdown(); 
      try{
          if (!submissionSerice.awaitTermination(10, TimeUnit.SECONDS)) {
              submissionSerice.shutdownNow();
              // Optional: wait again to ensure interruption is processed
              if (!submissionSerice.awaitTermination(5, TimeUnit.SECONDS)) {
                  System.err.println("Executor did not terminate");
              }
          }
	} catch (InterruptedException e) {
		submissionSerice.shutdownNow();
        Thread.currentThread().interrupt();
	}
    }
}