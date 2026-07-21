package com.tcs.fincore.AsciiGenerationService.service;

import com.tcs.fincore.AsciiGenerationService.dto.FileJob;
import com.tcs.fincore.AsciiGenerationService.dto.ReportGenerationResponseDTO;
import com.tcs.fincore.AsciiGenerationService.exception.BatchInitiationException;
import com.tcs.fincore.AsciiGenerationService.exception.ConfigNotFoundException;
import com.tcs.fincore.AsciiGenerationService.exception.FileProcessingException;
import com.tcs.fincore.AsciiGenerationService.kafka.ReportKafkaProducer;
import com.tcs.fincore.AsciiGenerationService.model.AsciiConfig;
import com.tcs.fincore.AsciiGenerationService.repository.AsciiConfigRepository;
import com.tcs.fincore.AsciiGenerationService.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class AsciiGenerationService {

//    private final AsciiGenerationService asciiService;
    private final AsciiConfigRepository configRepo;
    private final JobQueueManager jobQueueManager;
    private final HdfsService hdfsService;
    private final ReportKafkaProducer producer;

    private static final Map<String, Pattern> INPUT_REGEX_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, BatchStats> BATCH_TRACKER = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Object>> ASCII_STATUS_TRACKER = new ConcurrentHashMap<>();

    private final ThreadPoolExecutor submissionService = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(3),
            new ThreadPoolExecutor.AbortPolicy()
    );

    private static class BatchStats {
        private final long startTime;
        private final int totalFiles;
        private final AtomicInteger remainingFiles;
        private final AtomicInteger failedFiles = new AtomicInteger(0);
        private final ReportGenerationResponseDTO eventTemplate;

        BatchStats(int total, ReportGenerationResponseDTO eventTemplate) {
            this.startTime = System.currentTimeMillis();
            this.totalFiles = total;
            this.remainingFiles = new AtomicInteger(total);
            this.eventTemplate = eventTemplate;
        }
    }

    @Value("${app.input.base.path}")
    private String basePath;

    public String initiateBatch(Long configId, String date) {
        return initiateBatch(configId, date, null);
    }

    public String initiateBatch(Long configId, String date, ReportGenerationResponseDTO eventTemplate) {
        String batchId = UUID.randomUUID().toString();
        CompletableFuture<Object> future = new CompletableFuture<>();
        submissionService.submit(() -> {
            try {
                AsciiConfig config = loadConfig(configId);
                FileSystem fs = hdfsService.getFs();
                String reportId = config.getReportId();
                Path baseDir = new Path(basePath + "/" + date + "/" + reportId);

                log.info("Scanning ASCII input directory. configId={}, reportId={}, date={}, path={}", configId, reportId, date, baseDir);
                RemoteIterator<LocatedFileStatus> files = fs.listFiles(baseDir, true);
                List<Path> foundFiles = new ArrayList<>(35_000);

                while (files.hasNext()) {
                    LocatedFileStatus status = files.next();
                    if (status.isFile() && status.getPath().getName().endsWith(".psv")) {
                        foundFiles.add(status.getPath());
                    }
                }

                if (foundFiles.isEmpty()) {
                    log.warn("No PSV input files found. configId={}, reportId={}, date={}, path={}", configId, reportId, date, baseDir);
                    return future.complete(null);
                }

//            String batchId = UUID.randomUUID().toString();
                BATCH_TRACKER.put(batchId, new BatchStats(foundFiles.size(), eventTemplate));
                updateBatchStatus(batchId, Constants.GENERATING, foundFiles.size(), 0, foundFiles.size(), "Batch started");
                sendBatchEvent(batchId, Constants.GENERATING, "Batch started with " + foundFiles.size() + " files", eventTemplate);

                for (Path p : foundFiles) {
                    jobQueueManager.submitFileJob(new FileJob(configId, p, batchId, date));
//                asciiService.processSingleFile(new FileJob(configId, p, batchId, date));
                }

                log.info("ASCII batch started. batchId={}, configId={}, reportId={}, filesQueued={}", batchId, configId, reportId, foundFiles.size());
                return future.complete(null);
            } catch (IOException ex) {
                log.error("ASCII batch initiation failed due to HDFS I/O. configId={}, date={}", configId, date, ex);
                return future.complete(ex);
            }
        });
        future.exceptionally((e)->{
            log.info("Execption Occurred!");
            return null;
        });
        return "Batch Added: " + batchId;
//        catch (IOException ex) {
//            log.error("ASCII batch initiation failed due to HDFS I/O. configId={}, date={}", configId, date, ex);
//            throw new BatchInitiationException("Unable to scan ASCII input files", ex);
//        }
    }

    public Map<String, Object> getBatchStatus(String batchId) {
        return ASCII_STATUS_TRACKER.get(batchId);
    }

    public void processSingleFile(FileJob job) {
        try {
            processSingleFileInternal(job);
            markFileCompleted(job, true, null);
        } catch (FileProcessingException | ConfigNotFoundException ex) {
            log.error("ASCII file processing failed. batchId={}, configId={}, file={}", job.getBatchId(), job.getConfigId(), job.getFilePath(), ex);
            markFileCompleted(job, false, ex.getMessage());
        }
    }

    private void processSingleFileInternal(FileJob job) {
        try {
            FileSystem fs = hdfsService.getFs();
            AsciiConfig config = loadConfig(job.getConfigId());
            Path inputPath = job.getFilePath();
            String date = job.getDate();
            String reportId = config.getReportId();
            Path outputDir = new Path(basePath + "/" + date + "/ascii_files/" + reportId + "/");

            if (!fs.exists(outputDir)) {
                fs.mkdirs(outputDir);
                log.info("Created ASCII output directory. path={}", outputDir);
            }

            HeaderInfo header = parseHeaderEfficiently(fs, inputPath);
            String branchCode = String.format("%5s", header.branchCode).replace(' ', '0');
//            Path outputFile = new Path(outputDir, branchCode + "." + config.getOutputFileName() + "." + header.reportDate + "." + config.getFileType());

            String fileType = config.getFileType();
            StringBuilder fileNameBuilder = new StringBuilder()
                    .append(branchCode).append(".")
                    .append(config.getOutputFileName()).append(".")
                    .append(header.reportDate);


            if (fileType != null && !fileType.isEmpty()) {
                fileNameBuilder.append(".").append(fileType);
            }
            log.info("Filename : {}",fileNameBuilder.toString());
            Path outputFile = new Path(outputDir, fileNameBuilder.toString());

            String headerLine = config.getOutputFirstLine() + branchCode + header.reportDate + "F";
            List<String> allLines = readAndTransformRecords(fs, inputPath, config);

            allLines.sort(Comparator.comparingInt(this::safeLeadingHead));
            writeAsciiOutput(fs, outputFile, headerLine, allLines, config.getOutputEndLine(), config.getOutputPerLineHead());
            log.info("ASCII file completed. batchId={}, input={}, output={}, records={}", job.getBatchId(), inputPath, outputFile, allLines.size());
        } catch (IOException | IllegalArgumentException ex) {
            throw new FileProcessingException("Unable to process ASCII file " + job.getFilePath() + ": " + ex.getMessage(), ex);
        }
    }

    private AsciiConfig loadConfig(Long configId) {
        Optional<AsciiConfig> config = configRepo.findById(configId);
        return config.orElseThrow(() -> new ConfigNotFoundException(configId));
    }

    private List<String> readAndTransformRecords(FileSystem fs, Path inputPath, AsciiConfig config) throws IOException {
        List<String> allLines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(fs.open(inputPath), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    String processed = transformLine(line, config);
                    if (processed != null) {
                        allLines.add(processed);
                    }
                } catch (IllegalArgumentException | ArithmeticException ex) {
                    log.error("Skipping invalid ASCII row. inputFile={}, row={}, reason={}", inputPath, line, ex.getMessage(), ex);
                }
            }
        }
        return allLines;
    }

//    private void writeAsciiOutput(FileSystem fs, Path outputFile, String headerLine, List<String> records, String footerLine) throws IOException {
//        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(fs.create(outputFile, true), StandardCharsets.UTF_8))) {
//            writer.write(headerLine);
//            writer.newLine();
//            for (String record : records) {
//                writer.write(record);
//                writer.newLine();
//            }
//            if (footerLine != null) {
//                writer.write(footerLine);
//            }
//        }
//    }

    private void writeAsciiOutput(FileSystem fs, Path outputFile, String headerLine, List<String> records, String footerLine, Integer outputPerLineHead) throws IOException {
        // Default to 1 item per line if config is missing or invalid
        int itemsPerLine = (outputPerLineHead != null && outputPerLineHead > 0) ? outputPerLineHead : 1;

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(fs.create(outputFile, true), StandardCharsets.UTF_8))) {
            // Write Header
            writer.write(headerLine);
            writer.newLine();

            StringBuilder currentLineBuffer = new StringBuilder();
            int currentItemCount = 0;

            for (String record : records) {
                currentLineBuffer.append(record);
                currentItemCount++;

                // When we reach the configured number of heads per line, flush to file
                if (currentItemCount == itemsPerLine) {
                    writer.write(currentLineBuffer.toString());
                    writer.newLine();

                    // Reset buffer and counter for the next line
                    currentLineBuffer.setLength(0);
                    currentItemCount = 0;
                }
            }

            // Flush any remaining items that didn't form a complete batch
            if (currentItemCount > 0) {
                writer.write(currentLineBuffer.toString());
                writer.newLine();
            }

            // Write Footer if present
            if (footerLine != null && !footerLine.isEmpty()) {
                writer.write(footerLine);
            }
        }
    }

    private int safeLeadingHead(String record) {
        if (record == null || record.length() < 5) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(record.substring(0, 5));
        } catch (NumberFormatException ex) {
            return Integer.MAX_VALUE;
        }
    }

    private void markFileCompleted(FileJob job, boolean success, String reason) {
        BatchStats stats = BATCH_TRACKER.get(job.getBatchId());
        if (stats == null) {
            return;
        }
        if (!success) {
            stats.failedFiles.incrementAndGet();
        }
        int remaining = stats.remainingFiles.decrementAndGet();
        updateBatchStatus(job.getBatchId(), Constants.GENERATING, stats.totalFiles, stats.failedFiles.get(), remaining, "Batch processing");
        if (remaining == 0) {
            int failed = stats.failedFiles.get();
            String status = failed == 0 ? Constants.SUCCESS : (failed < stats.totalFiles ? Constants.PARTIAL_SUCCESS : Constants.FAILED);
            long durationMs = System.currentTimeMillis() - stats.startTime;
            String remark = String.format("Batch completed. totalFiles=%d, failedFiles=%d, durationMs=%d", stats.totalFiles, failed, durationMs);
            if (reason != null && failed == stats.totalFiles) {
                remark = remark + ", lastError=" + reason;
            }
            updateBatchStatus(job.getBatchId(), status, stats.totalFiles, failed, 0, remark);
            sendBatchEvent(job.getBatchId(), status, remark, stats.eventTemplate);
            BATCH_TRACKER.remove(job.getBatchId());
            log.info("ASCII batch completed. batchId={}, status={}, totalFiles={}, failedFiles={}, durationMs={}", job.getBatchId(), status, stats.totalFiles, failed, durationMs);
        }
    }

    private void updateBatchStatus(String batchId, String status, int totalFiles, int failedFiles, int remainingFiles, String remark) {
        Map<String, Object> state = new ConcurrentHashMap<>();
        state.put("batchId", batchId);
        state.put("status", status);
        state.put("totalFiles", totalFiles);
        state.put("failedFiles", failedFiles);
        state.put("remainingFiles", remainingFiles);
        state.put("remark", remark);
        ASCII_STATUS_TRACKER.put(batchId, state);
    }

    private void sendBatchEvent(String batchId, String status, String remark, ReportGenerationResponseDTO template) {
        if (template == null) {
            return;
        }
        ReportGenerationResponseDTO event = new ReportGenerationResponseDTO();
        event.setProcessRunId(template.getProcessRunId());
        event.setStageId(template.getStageId());
        event.setRunId(template.getRunId());
        event.setType(template.getType());
        event.setId(template.getId());
        event.setReportDate(template.getReportDate());
        event.setStatus(status);
        event.setRemark(remark + "; batchId=" + batchId);
        producer.sendResponse(event);
    }

    private String transformLine(String line, AsciiConfig config) {
        String[] columns = line.split("\\|", -1);
        if (!isValidRow(columns, config)) {
            return null;
        }

        List<ProcessingItem> items = extractAmountColumns(columns, config);
        StringBuilder sb = new StringBuilder();
        boolean valid = false;

        for (ProcessingItem item : items) {
            BigDecimal finalAmt = RuleParser.applyMathLogic(item.amount, item.head, item.colIndex, config.getOutputAmtColLogic());
            finalAmt = RuleParser.applyDecimalLogic(finalAmt, item.head, item.colIndex, config.getOutputAmtDecimal());
            if (RuleParser.shouldIncludeValue(finalAmt, item.head, item.colIndex, config.getOutputIncludeCondition())) {
                String amtStr = RuleParser.applyAmountPadding(finalAmt, item.head, item.colIndex, config.getOutputAmtColPad());
                String signed = RuleParser.applySign(amtStr, finalAmt, item.head, item.colIndex, config.getOutputAmtSign());
                sb.append(signed);
                valid = true;
            }
        }

        if (!valid) {
            return null;
        }
        String headPadded = RuleParser.applyHeadPadding(items.get(0).head, config.getOutputHeadColPad());
        return headPadded + sb;
    }

    private List<ProcessingItem> extractAmountColumns(String[] cols, AsciiConfig config) {
        List<ProcessingItem> items = new ArrayList<>();
        int headIdx = config.getInputHeadCol() - 1;
        String head = (headIdx < cols.length) ? cols[headIdx].trim() : "";
        String seq = config.getAmountColSeq();

        if (seq.contains("else")) {
            String[] parts = seq.split("else");
            int primary = Integer.parseInt(parts[0].trim());
            for (String p : parts) {
                int idx = Integer.parseInt(p.trim()) - 1;
                BigDecimal val = parseAmount(cols, idx);
                if (val.compareTo(BigDecimal.ZERO) != 0) {
                    items.add(new ProcessingItem(head, val, primary));
                    return items;
                }
            }
            items.add(new ProcessingItem(head, BigDecimal.ZERO, primary));
        } else if (seq.contains("and")) {
            for (String p : seq.split("and")) {
                int idx = Integer.parseInt(p.trim()) - 1;
                items.add(new ProcessingItem(head, parseAmount(cols, idx), idx + 1));
            }
        } else {
            int idx = Integer.parseInt(seq.trim()) - 1;
            items.add(new ProcessingItem(head, parseAmount(cols, idx), idx + 1));
        }

        return items;
    }

    private BigDecimal parseAmount(String[] cols, int idx) {
        if (idx >= cols.length) {
            return BigDecimal.ZERO;
        }
        return parseAmount(cols[idx]);
    }

    private BigDecimal parseAmount(String val) {
        if (val == null || val.trim().isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(val.trim().replace(",", ""));
        } catch (NumberFormatException ex) {
            log.warn("Invalid amount value encountered. value={}", val);
            return BigDecimal.ZERO;
        }
    }

    private boolean isValidRow(String[] cols, AsciiConfig config) {
        int idx = config.getInputHeadCol() - 1;
        if (cols.length <= idx) {
            return false;
        }
        String head = cols[idx].trim();
        Pattern p = INPUT_REGEX_CACHE.computeIfAbsent(config.getInputHeadRegex(), Pattern::compile);
        return p.matcher(head).lookingAt();
    }

    private HeaderInfo parseHeaderEfficiently(FileSystem fs, Path path) throws IOException {
        HeaderInfo info = new HeaderInfo();
        info.branchCode = "00000";
        info.reportDate = "";

        Pattern branchPattern = Pattern.compile("(?i)BRANCH\\s*::\\s*(\\d+)");
        Pattern datePattern = Pattern.compile("REPORT DATE\\s*::\\s*(\\S+)");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(fs.open(path), StandardCharsets.UTF_8))) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count++ < 50) {
                Matcher branchMatcher = branchPattern.matcher(line);
                if (branchMatcher.find()) {
                    info.branchCode = branchMatcher.group(1);
                }
                Matcher dateMatcher = datePattern.matcher(line);
                if (dateMatcher.find()) {
                    info.reportDate = normalizeDate(dateMatcher.group(1));
                }
            }
        }
        return info;
    }

    private String normalizeDate(String d) {
        try {
            return LocalDate.parse(d, DateTimeFormatter.ofPattern("yyyy-MM-dd")).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        } catch (DateTimeParseException ex) {
            log.warn("Invalid report date found in ASCII header. value={}", d);
            return "";
        }
    }

    private static class ProcessingItem {
        private final String head;
        private final BigDecimal amount;
        private final int colIndex;

        ProcessingItem(String h, BigDecimal a, int c) {
            head = h;
            amount = a;
            colIndex = c;
        }
    }

    private static class HeaderInfo {
        private String branchCode;
        private String reportDate;
    }
}