package com.tcs.fincore.AsciiGenerationService.service;

import com.google.common.collect.Lists;
import com.tcs.fincore.AsciiGenerationService.dto.TbAsciiBatchRequestDto;
import com.tcs.fincore.AsciiGenerationService.dto.TbAsciiBatchRequestPayload;
import com.tcs.fincore.AsciiGenerationService.dto.TbAsciiGenReqStatusDTO;
import com.tcs.fincore.AsciiGenerationService.exception.PathValidationException;
import com.tcs.fincore.AsciiGenerationService.exception.TbAsciiGenerationException;
import com.tcs.fincore.AsciiGenerationService.util.ReportStatus;
import jakarta.annotation.PreDestroy;
import org.apache.commons.collections4.map.SingletonMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlOutParameter;
import org.springframework.jdbc.core.SqlParameter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class TbAsciiService {
    private static final String TOPIC = "tb_ascii-report-generation-request-status";
    private static final Logger log = LoggerFactory.getLogger(TbAsciiService.class);
    private static final Semaphore DB_SEMAPHORE = new Semaphore(15, true);
    private static final int BATCH_SIZE = 100;
    private final ExecutorService submissionService = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(3),
            new ThreadPoolExecutor.AbortPolicy()
    );

    private final JdbcTemplate jdbcTemplate;
    private final TbAsciiGenerationService tbAsciiGenerationService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ConcurrentHashMap<String, TbAsciiGenReqStatusDTO> statusTracker = new ConcurrentHashMap<>();

    @Autowired
    public TbAsciiService(JdbcTemplate jdbcTemplate,
                          KafkaTemplate<String, Object> kafkaTemplate,
                          TbAsciiGenerationService tbAsciiGenerationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tbAsciiGenerationService = tbAsciiGenerationService;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Async("tbTaskExecutor")
    public void generateSingleFileAsync(String reportId, String branchCode, String dateStr, String outputDir) {
        LocalDate date;
        try {
            date = LocalDate.parse(dateStr);
        } catch (DateTimeParseException ex) {
            log.error("Invalid TB ASCII date. date={}", dateStr, ex);
            return;
        }

        String safeDate = date.format(DateTimeFormatter.BASIC_ISO_DATE);
        String fileName = String.format("%s_%s_%s.txt", reportId, branchCode, safeDate);
        Path targetPath;
        try {
            Path baseDir = Paths.get(outputDir).normalize();
            if (!Files.exists(baseDir)) {
                Files.createDirectories(baseDir);
            }
            targetPath = baseDir.resolve(fileName).normalize();
            if (!targetPath.startsWith(baseDir)) {
                throw new PathValidationException("Path traversal detected");
            }
        } catch (IOException | PathValidationException ex) {
            log.error("TB ASCII path error. branchCode={}, outputDir={}", branchCode, outputDir, ex);
            return;
        }

        try {
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
                    } catch (IOException ex) {
                        throw new SQLException("Unable to write TB ASCII single-file output", ex);
                    }
                }
                return null;
            });
        } catch (DataAccessException ex) {
            log.error("TB ASCII single-file DB error. fileName={}", fileName, ex);
        }
    }

    private void processTBAsciiGeneration(TbAsciiGenReqStatusDTO reqStatusDTO,
                                          TbAsciiBatchRequestPayload payload,
                                          String runId,
                                          LocalDate date,
                                          int totalReports) {
        long startTime = System.nanoTime();
        AtomicInteger connectionFailure = new AtomicInteger(0);
        reqStatusDTO.setStatus(ReportStatus.GENERATING);
        reqStatusDTO.setStartTime(Instant.now().toString());
        saveAndSendEvent(reqStatusDTO);

        List<CompletableFuture<String>> futures = new ArrayList<>();
        ConcurrentHashMap<String, ConcurrentHashMap<String, Integer>> batchStatus = new ConcurrentHashMap<>();

        for (String reportId : payload.getReportIds()) {
            TemplateIds templateIds;
            try {
                templateIds = parseTemplate(reportId, runId);
            } catch (TbAsciiGenerationException ex) {
                log.error("Skipping TB ASCII report because template parsing failed. reportId={}, runId={}", reportId, runId, ex);
                futures.add(CompletableFuture.completedFuture(reportId + "!" + payload.getBranchCodes().size()
                        + System.lineSeparator() + ex.getMessage()));
                continue;
            }
            log.info("Generating TB ASCII report. reportId={}, branches={}, batchSize={}", reportId, payload.getBranchCodes().size(), BATCH_SIZE);
            ConcurrentHashMap<String, Integer> perReportStatus = new ConcurrentHashMap<>();
            perReportStatus.put("report_processed", 0);
            batchStatus.put(reportId, perReportStatus);
            AtomicBoolean checkedPathTraversal = new AtomicBoolean(false);

            for (List<String> codes : Lists.partition(payload.getBranchCodes(), BATCH_SIZE)) {
                if (connectionFailure.get() >= 3) {
                    break;
                }
                acquireDbPermit(runId, reportId, codes.size());
                futures.add(tbAsciiGenerationService.processBatch(
                        reportId,
                        runId,
                        codes,
                        payload.getBalanceDate(),
                        templateIds.headerId(),
                        templateIds.footerId(),
                        date,
                        checkedPathTraversal
                ).handle((result, throwable) -> {
                    DB_SEMAPHORE.release();
                    perReportStatus.merge("report_processed", codes.size(), Integer::sum);
                    if (throwable != null) {
                        if (throwable.getMessage() != null && throwable.getMessage().contains("Connection Failed")) {
                            connectionFailure.incrementAndGet();
                        }
                        log.error("TB ASCII batch failed. reportId={}, branches={}", reportId, codes, throwable);
                        return reportId + "!" + codes.size() + System.lineSeparator()
                                + "Report generation task failed for branchCodes: " + codes + ". Reason: " + throwable.getMessage();
                    }
                    if (result == null) {
                        return null;
                    }
                    log.error("TB ASCII batch completed with errors. reportId={}, branches={}", reportId, codes);
                    return reportId + "!" + result;
                }));
            }
            if (connectionFailure.get() >= 3) {
                log.error("Stopping TB ASCII submission because repeated connection failures occurred. runId={}", runId);
                break;
            }
        }

        try {
            Map<String, Map<String, Object>> result = aggregateResults(futures).get();
            addAbortedReportMetrics(payload, batchStatus, result);
            reqStatusDTO.setMetrics(result);
            reqStatusDTO.setStatus(resolveFinalStatus(result, totalReports));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.error("TB ASCII aggregation interrupted. runId={}", runId, ex);
            reqStatusDTO.setStatus(ReportStatus.FAILED);
            reqStatusDTO.setMessage("TB ASCII aggregation interrupted");
        } catch (ExecutionException ex) {
            log.error("TB ASCII aggregation failed. runId={}", runId, ex);
            reqStatusDTO.setStatus(ReportStatus.FAILED);
            reqStatusDTO.setMessage("TB ASCII aggregation failed: " + ex.getMessage());
        }

        reqStatusDTO.setEndTime(Instant.now().toString());
        saveAndSendEvent(reqStatusDTO);
        long durationInMillis = (System.nanoTime() - startTime) / 1_000_000;
        log.info("Completed TB ASCII batch. runId={}, status={}, durationMs={}", runId, reqStatusDTO.getStatus(), durationInMillis);
    }

    public SingletonMap<ReportStatus, String> generateFiles(TbAsciiBatchRequestDto req, String creationMethod) {
        TbAsciiBatchRequestPayload payload = req.getPayload();
        int totalReports = payload.getReportIds().size() * payload.getBranchCodes().size();
        TbAsciiGenReqStatusDTO reqStatusDTO = new TbAsciiGenReqStatusDTO(req.getProcessRunId(), req.getStageId(), req.getRunId(), req.getType(), creationMethod);
        String runId = buildRunId(req);
        statusTracker.put(runId, reqStatusDTO);

        try {
            LocalDate date = LocalDate.parse(payload.getBalanceDate());
            reqStatusDTO.setStatus(ReportStatus.QUEUED);
            reqStatusDTO.setMessage(String.format("Batch queued. totalReports=%d", totalReports));
            saveAndSendEvent(reqStatusDTO);
            submissionService.submit(() -> processTBAsciiGeneration(reqStatusDTO, payload, runId, date, totalReports));
            return new SingletonMap<>(ReportStatus.QUEUED, String.format("Batch of total:%d reports submitted!", totalReports));
        } catch (DateTimeParseException ex) {
            log.error("Invalid TB ASCII date. date={}", payload.getBalanceDate(), ex);
            reqStatusDTO.setStatus(ReportStatus.FAILED);
            reqStatusDTO.setMessage("Invalid Date");
            saveAndSendEvent(reqStatusDTO);
            return new SingletonMap<>(ReportStatus.FAILED, "Invalid Date Provided!");
        } catch (RejectedExecutionException ex) {
            log.error("TB ASCII request rejected because submission queue is full. runId={}", runId, ex);
            reqStatusDTO.setStatus(ReportStatus.REJECTED);
            reqStatusDTO.setMessage("Submission queue is full");
            saveAndSendEvent(reqStatusDTO);
            return new SingletonMap<>(ReportStatus.REJECTED, "Batch generation rejected because prior requests are already queued.");
        }
    }

    public TbAsciiGenReqStatusDTO getStatus(String runId) {
        return statusTracker.get(runId);
    }

    private TemplateIds parseTemplate(String reportId, String runId) {
        SimpleJdbcCall jdbcCall = new SimpleJdbcCall(jdbcTemplate)
                .withProcedureName("SP_PARSE_TB_TEMPLATE_N1")
                .declareParameters(
                        new SqlParameter("p_report_id", Types.VARCHAR),
                        new SqlParameter("p_run_id", Types.VARCHAR),
                        new SqlOutParameter("p_header_id", Types.VARCHAR),
                        new SqlOutParameter("p_footer_id", Types.VARCHAR)
                );
        MapSqlParameterSource in = new MapSqlParameterSource()
                .addValue("p_report_id", reportId)
                .addValue("p_run_id", runId);
        try {
            Map<String, Object> out = jdbcCall.execute(in);
            log.info("Parsed TB ASCII template. reportId={}, runId={}", reportId, runId);
            return new TemplateIds((String) out.get("p_header_id"), (String) out.get("p_footer_id"));
        } catch (DataAccessException ex) {
            log.error("Failed parsing TB ASCII template. reportId={}, runId={}", reportId, runId, ex);
            throw new TbAsciiGenerationException("Unable to parse TB template for report: " + reportId, ex);
        }
    }

    private CompletableFuture<Map<String, Map<String, Object>>> aggregateResults(List<CompletableFuture<String>> futures) {
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> futures.stream()
                        .map(CompletableFuture::join)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toMap(
                                line -> line.split("!")[0].trim(),
                                this::toReportErrorMetric,
                                this::mergeReportErrorMetric
                        )));
    }

    private Map<String, Object> toReportErrorMetric(String line) {
        String[] parts = line.split("!", 2);
        String[] lines = parts[1].split(System.lineSeparator());
        int initialCount = Integer.parseInt(lines[0]);
        List<String> errorList = Arrays.stream(lines).skip(1).map(String::trim).toList();
        Map<String, Object> reportErr = new HashMap<>();
        reportErr.put("failedCount", initialCount);
        reportErr.put("errors", new ArrayList<>(errorList));
        return reportErr;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeReportErrorMetric(Map<String, Object> existing, Map<String, Object> incoming) {
        int currentCount = (int) existing.get("failedCount");
        int incomingCount = (int) incoming.get("failedCount");
        existing.put("failedCount", currentCount + incomingCount);
        List<String> existingErrors = (List<String>) existing.get("errors");
        existingErrors.addAll((List<String>) incoming.get("errors"));
        return existing;
    }

    @SuppressWarnings("unchecked")
    private void addAbortedReportMetrics(TbAsciiBatchRequestPayload payload,
                                         ConcurrentHashMap<String, ConcurrentHashMap<String, Integer>> batchStatus,
                                         Map<String, Map<String, Object>> result) {
        payload.getReportIds().forEach(reportId -> {
            ConcurrentHashMap<String, Integer> perReportStat = batchStatus.get(reportId);
            int processed = perReportStat == null ? 0 : perReportStat.getOrDefault("report_processed", 0);
            int abortedCount = payload.getBranchCodes().size() - processed;
            if (!result.containsKey(reportId)) {
                Map<String, Object> reportState = new HashMap<>();
                reportState.put("failedCount", Math.max(abortedCount, 0));
                List<String> reportErrors = new ArrayList<>();
                if (abortedCount > 0) {
                    reportErrors.add("Report generation aborted before all branch codes were processed.");
                }
                reportState.put("errors", reportErrors);
                result.put(reportId, reportState);
            } else if (abortedCount > 0) {
                Map<String, Object> state = result.get(reportId);
                state.put("failedCount", (int) state.getOrDefault("failedCount", 0) + abortedCount);
                ((List<String>) state.get("errors")).add("Report generation aborted for remaining branch codes. abortedCount=" + abortedCount);
            }
        });
    }

    private ReportStatus resolveFinalStatus(Map<String, Map<String, Object>> result, int totalReports) {
        int totalErrors = result.values().stream().mapToInt(map -> (int) map.get("failedCount")).sum();
        if (totalErrors == 0) {
            return ReportStatus.SUCCESS;
        }
        if (totalErrors < totalReports) {
            return ReportStatus.PARTIALLY_FAILED;
        }
        return ReportStatus.FAILED;
    }

    private void acquireDbPermit(String runId, String reportId, int batchSize) {
        try {
            DB_SEMAPHORE.acquire();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TbAsciiGenerationException("Interrupted while waiting for DB permit. runId=" + runId + ", reportId=" + reportId + ", batchSize=" + batchSize, ex);
        }
    }

    private String buildRunId(TbAsciiBatchRequestDto req) {
        return req.getProcessRunId() + "_" + req.getStageId() + "_" + req.getRunId();
    }

    @PreDestroy
    public void closeConnection() {
        submissionService.shutdown();
        try {
            if (!submissionService.awaitTermination(10, TimeUnit.SECONDS)) {
                submissionService.shutdownNow();
                if (!submissionService.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.error("TB ASCII submission executor did not terminate cleanly");
                }
            }
        } catch (InterruptedException ex) {
            submissionService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void saveAndSendEvent(TbAsciiGenReqStatusDTO status) {
        statusTracker.put(status.compositeRunId(), status);
        if ("KAFKA-Scheduled".equalsIgnoreCase(status.getCreationMethod())) {
            kafkaTemplate.send(TOPIC, status).whenComplete((res, err) -> {
                if (err != null) {
                    log.error("Failed to publish TB ASCII status event. topic={}, runId={}, status={}", TOPIC, status.compositeRunId(), status.getStatus(), err);
                } else {
                    log.info("Published TB ASCII status event. topic={}, runId={}, status={}", TOPIC, status.compositeRunId(), status.getStatus());
                }
            });
        }
    }

    private record TemplateIds(String headerId, String footerId) {
    }
}
