package com.tcs.fincore.AsciiGenerationService.service;

import com.google.common.collect.Lists;
import com.tcs.fincore.AsciiGenerationService.dto.TbAsciiBatchRequestDto;
import com.tcs.fincore.AsciiGenerationService.dto.TbAsciiBatchRequestPayload;
import com.tcs.fincore.AsciiGenerationService.dto.TbAsciiGenReqStatusDTO;
import com.tcs.fincore.AsciiGenerationService.util.ReportStatus;
import jakarta.annotation.PreDestroy;
import org.apache.commons.collections4.map.SingletonMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlOutParameter;
import org.springframework.jdbc.core.SqlParameter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class TbAsciiService {
    private static String topic = "tb_ascii-report-generation-request-status";
    private static final Logger log = LoggerFactory.getLogger(TbAsciiService.class);
    private static final Semaphore DB_SEMAPHORE = new Semaphore(15,true);
    private static final int BATCH_SIZE = 50;
    private final ExecutorService submissionSerice =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(3),
                    new ThreadPoolExecutor.AbortPolicy() // Throws RejectedExecutionException
            );
    //    private final ThreadPoolTaskExecutor executor;
    private final JdbcTemplate jdbcTemplate;

    private final TbAsciiGenerationService tbAsciiGenerationService;
    private final KafkaTemplate kafkaTemplate;

    @Autowired
    public TbAsciiService(JdbcTemplate jdbcTemplate,
                          KafkaTemplate kafkaTemplate,
                          TbAsciiGenerationService tbAsciiGenerationService
//                          ThreadPoolTaskExecutor executor
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.tbAsciiGenerationService = tbAsciiGenerationService;
        this.kafkaTemplate = kafkaTemplate;
//        this.executor=executor;
    }

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

        //log.info("[TB] Generating: {}", targetPath);


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
                //log.info("[TB] DONE: {}", fileName);
            } catch (Exception e) {
                log.error("[TB] DB Error {}: {}", fileName, e.getMessage());
            }
            return null;
        });
    }

    private void processTBAsciiGeneration(
            TbAsciiGenReqStatusDTO reqStatusDTO,
            TbAsciiBatchRequestPayload payload,
            String run_id,
            LocalDate date,
            int totalReports
    ) {
        long startTime = System.nanoTime();
        AtomicInteger connectionFailure = new AtomicInteger(0);
        reqStatusDTO.setStatus(ReportStatus.GENERATING);
        reqStatusDTO.setStartTime(Instant.now().toString());
        sendEvent(topic, reqStatusDTO);
        Map<String, Object> metrics = new HashMap<>();
        StringBuilder errors = new StringBuilder();
        List<CompletableFuture<String>> futures = new ArrayList<>();

                /*
                batch reportId
                 */
        ConcurrentHashMap<String, ConcurrentHashMap<String, Integer>> batchStatus = new ConcurrentHashMap<>();
        for (String reportId : payload.getReportIds()) {
            SimpleJdbcCall jdbcCall = new SimpleJdbcCall(jdbcTemplate)
                    .withProcedureName("SP_PARSE_TB_TEMPLATE_N1").declareParameters(
                            new SqlParameter("p_report_id", Types.VARCHAR),
                            new SqlParameter("p_run_id", Types.VARCHAR), // IN
                            new SqlOutParameter("p_header_id", Types.VARCHAR),
                            new SqlOutParameter("p_footer_id", Types.VARCHAR)
                    );
            MapSqlParameterSource in = new MapSqlParameterSource().addValue("p_report_id", reportId).addValue("p_run_id", run_id);
            Map<String, Object> out = new HashMap<>();
            try {
                out = jdbcCall.execute(in);
            } catch (Exception e) {
                log.error("Error while parsing a template for the report: {}", reportId);
            }
            log.info("Parsed template for the report: {}", reportId);
            String p_header_id = (String) out.get("p_header_id");
            String p_footer_id = (String) out.get("p_footer_id");
            log.info("Generating {} reports in batch of size : {} ", reportId, BATCH_SIZE);
            Collection<List<String>> batches = Lists.partition(payload.getBranchCodes(), BATCH_SIZE);
//                                                    //can be used for status tracking
            /* processing the batches in controlled manner using semaphores and storing the futures in array for further error processing  */
            ConcurrentHashMap<String, Integer> perReportStatus = new ConcurrentHashMap<>();
            perReportStatus.put("report_processed", 0);
            batchStatus.put(reportId, perReportStatus);

            AtomicBoolean checkedPathTraversal = new AtomicBoolean(false);
            for (List<String> codes : batches) {
                if (connectionFailure.intValue() == 3) {
                    break;
                }
                boolean acquired = false;
                while (!acquired) {
                    try {
                        DB_SEMAPHORE.acquire();
                        acquired = true;
                    } catch (InterruptedException e) {
                        log.info("Waiting for permit interrupted! trying to acquire permit again..");
                    }
                }
                futures.add(
                        tbAsciiGenerationService.processBatch(
                                reportId,
                                run_id,
                                codes,
                                payload.getBalanceDate(),
                                p_header_id,
                                p_footer_id,
                                date,
                                checkedPathTraversal
                        ).handle((result, exception) -> {
                                    DB_SEMAPHORE.release(); // Always release
                                    perReportStatus.merge("report_processed", codes.size(), Integer::sum);
                                    if (exception != null) {
                                        if (exception.getMessage().contains("Connection Failed")) {
                                            connectionFailure.addAndGet(1);
                                        }
                                        return reportId + "!" + codes.size() + System.lineSeparator() + "Report Generation task failed for batch_ids: " + codes.toString() + " . Reason: " + exception.toString();
                                    }
                                    if (result == null) {
                                        return null;
                                    }
                                    log.error("Errors found while generating TB ASCII for report ids: " + codes.toString());
                                    return reportId + "!" + result;
                                }
                        ));
            }
            if (connectionFailure.intValue() == 3) {
                log.info("Conn breaked!");
                break;
            }

        }

        /* aggregating batch result and generating the metrics */
        CompletableFuture<Map<String, Map<String, Object>>> aggregatedFuture = CompletableFuture.allOf(
                        futures.toArray(new CompletableFuture[0])
                )
                .thenApply((result) -> {
                    return futures
                            .stream()
                            .map(CompletableFuture::join)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toMap(
                                    line -> line.split("!")[0].trim(),
                                    line -> {
                                        System.out.println("line: " + line);
                                        String[] parts = line.split("!", 2);
                                        String[] lines = parts[1].split(System.lineSeparator());
//                                        String[] lines = parts[1].split("\\|");
                                        System.out.println(lines[0]);
                                        int initialCount = Integer.parseInt(lines[0]);
                                        List<String> errorList = Arrays.stream(lines).skip(1).map(String::trim).toList();

                                        Map<String, Object> reportErr = new HashMap<>();
                                        reportErr.put("failedCount", initialCount);
                                        reportErr.put("errors", errorList);
                                        return reportErr;
                                    },
                                    (existing, newMap) -> {
                                        int currentCount = (int) existing.get("failedCount");
                                        int incomingCount = (int) newMap.get("failedCount");
                                        existing.put("failedCount", currentCount + incomingCount);
                                        List<String> existingErrors = (List<String>) existing.get("errors");
                                        existingErrors.addAll((List<String>) newMap.get("errors"));
                                        return existing;
                                    }


                            ));


                });

        try {
            Map<String, Map<String, Object>> result = aggregatedFuture.get();   //waiting for batch completion
            log.info("processing!");

            /*  Logging the result for testing */
//            result.entrySet().forEach((value) -> {
//                log.info("Metrics for report: {}", value.getKey());
//                value.getValue().entrySet().forEach((entry) -> {
//                    log.info(entry.getKey() + " : " + entry.getValue());
//                });
//            });

            /* Handelling incomplete process */
//                    Map<String,Object> result=(Map<String,Object>) result.get("metrics");
            payload.getReportIds().forEach((key) -> {
                ConcurrentHashMap<String, Integer> perReportStat = ((ConcurrentHashMap<String, Integer>) batchStatus.get(key));
                log.info(perReportStat.get("report_processed").toString());
                int abortedReportIdCount = payload.getBranchCodes().size() - perReportStat.get("report_processed");
                if (!result.containsKey(key)) {
                    Map<String, Object> abortedReports = new HashMap<>();
                    if (abortedReportIdCount > 0) {
                        abortedReports.put("failedCount", payload.getBranchCodes().size());
                        List errorStr = new ArrayList<String>();
                        errorStr.add("Report generation for is aborted!");
                        abortedReports.put("errors", errorStr);
                    } else {
                        abortedReports.put("failedCount", 0);
                        abortedReports.put("errors", new ArrayList<String>());
                    }
                    result.put(key, abortedReports);
                } else {
                    if (abortedReportIdCount > 0) {
                        Map<String, Object> state = result.get(key);
                        int tempFailed = (int) state.getOrDefault("failedCount", 0) + abortedReportIdCount;
                        state.put("failedCount", tempFailed);
                        ((ArrayList<String>) state.get(errors)).add("Report Generation aborted for remaining reportIds!: " + payload.getBranchCodes().subList(payload.getBranchCodes().size() - abortedReportIdCount - 1, payload.getBranchCodes().size()).toString());
                    }
                }
            });

            reqStatusDTO.setMetrics(result);
            int totalErrors = result.values().stream().mapToInt(map -> ((int) map.get("failedCount"))).sum();
            if (totalErrors == 0) {
                reqStatusDTO.setStatus(ReportStatus.SUCCESS);
            } else if (totalErrors > 0 && totalErrors < totalReports) {
                reqStatusDTO.setStatus(ReportStatus.PARTIALLY_FAILED);
            } else {
                reqStatusDTO.setStatus(ReportStatus.FAILED);
            }
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
            log.info(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            log.info(e.getMessage());
        }
        reqStatusDTO.setEndTime(Instant.now().toString());
        sendEvent(topic, reqStatusDTO);
        long endTime = System.nanoTime();
        long durationInMillis = (endTime - startTime) / 1_000_000;
        log.info("completed the batch with run_id : {}  in {} ms", run_id, durationInMillis);
//            jdbcTemplate.update("delete from TB_BREAKUP_META_STAGE where run_id = ?", run_id);
    }

    /*
      Submitter method to submit task in Tb Ascii Generation service
     */
    public SingletonMap<ReportStatus, String> generateFiles(TbAsciiBatchRequestDto req, String creationMethod) {
        TbAsciiBatchRequestPayload payload = req.getPayload();
        int totalReports = payload.getReportIds().size() * payload.getBranchCodes().size();
        TbAsciiGenReqStatusDTO reqStatusDTO = new TbAsciiGenReqStatusDTO(req.getProcessRunId(), req.getStageId(), req.getRunId(), req.getType(), creationMethod);
        String run_id = req.getProcessRunId() + "_" + req.getStageId() + "_" + req.getRunId();

        //checks
        try {
            LocalDate date = LocalDate.parse(payload.getBalanceDate());
            //Non-Blocking batch submission TB ascii generation
            submissionSerice.submit(() -> processTBAsciiGeneration(reqStatusDTO, payload, run_id, date, totalReports));
            reqStatusDTO.setStatus(ReportStatus.QUEUED);
        } catch (DateTimeParseException e) {
            log.error("[2TB] Invalid Date: {}", payload.getBalanceDate());
            reqStatusDTO.setStatus(ReportStatus.FAILED);
            reqStatusDTO.setMessage("Invalid Date!");
            sendEvent(topic, reqStatusDTO);
            return new SingletonMap<>(ReportStatus.FAILED, "Invalid Date Provided!");
        } catch (RejectedExecutionException e) {
            reqStatusDTO.setStatus(ReportStatus.REJECTED);
            return new SingletonMap<>(ReportStatus.REJECTED, "Batch generation failed in background due to already queued requests.");
        }
//        String safeDate = LocalDate.parse(payload.getBalanceDate()).format(DateTimeFormatter.BASIC_ISO_DATE);

        return new SingletonMap<>(ReportStatus.GENERATING,String.format("Batch of total:%d reports submitted! ",totalReports));
    }


    @PreDestroy
    public void closeConnection() {
        submissionSerice.shutdown();
        try {
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

    public void sendEvent(String topic, TbAsciiGenReqStatusDTO status) {
        if (status.getCreationMethod().equals("KAFKA-Scheduled")) {
        kafkaTemplate.send(topic, status).whenComplete((res, err) -> {
            if (res != null) {
                log.error(res.toString());
            }
        });
        }
    }
}