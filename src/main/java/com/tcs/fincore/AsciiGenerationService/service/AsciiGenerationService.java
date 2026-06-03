package com.tcs.fincore.AsciiGenerationService.service;

import com.tcs.fincore.AsciiGenerationService.dto.FileJob;
import com.tcs.fincore.AsciiGenerationService.model.AsciiConfig;
import com.tcs.fincore.AsciiGenerationService.repository.AsciiConfigRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import org.apache.hadoop.fs.*;

import java.io.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;

import org.apache.hadoop.fs.FileSystem;

@Service
@Slf4j
public class AsciiGenerationService {

    @Autowired
    private AsciiConfigRepository configRepo;

    @Autowired
    private JobQueueManager jobQueueManager;

    @Autowired
    private HdfsService hdfsService;

    private static final Map<String, Pattern> INPUT_REGEX_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, BatchStats> batchTracker = new ConcurrentHashMap<>();

    private static class BatchStats {
        long startTime;
        AtomicInteger remainingFiles;

        BatchStats(int total) {
            this.startTime = System.currentTimeMillis();
            this.remainingFiles = new AtomicInteger(total);
        }
    }

    @Value("${app.input.base.path}")
    private String basePath;
    // =========================
    // 1. INITIATE BATCH
    // =========================

    public String initiateBatch(Long configId, String date) {
        try {
            AsciiConfig config = configRepo.findById(configId).orElseThrow(() -> new RuntimeException("Config not found"));

            FileSystem fs = hdfsService.getFs();
            String reportId = config.getReportId();
            Path baseDir = new Path(basePath + "/" + date + "/" + reportId);

            log.info("Scanning directory: {}", baseDir);

            // Single recursive RPC — lazy iterator, low heap
            RemoteIterator<LocatedFileStatus> files = fs.listFiles(baseDir, true);

            // Pre-size to avoid ArrayList rehashing (you know ~50k files)
            List<Path> foundFiles = new ArrayList<>(35_000);

            while (files.hasNext()) {
                LocatedFileStatus status = files.next();
                // isFile() guard avoids accidentally queueing directory paths
                if (status.isFile() && status.getPath().getName().endsWith(".psv")) {
                    foundFiles.add(status.getPath());
                }
            }

            if (foundFiles.isEmpty()) return "No files found";

            String batchId = UUID.randomUUID().toString();
            batchTracker.put(batchId, new BatchStats(foundFiles.size()));

            // Parallel submission — don't block on 50k sequential submitFileJob calls
            foundFiles.parallelStream().forEach(p -> jobQueueManager.submitFileJob(new FileJob(configId, p, batchId, date)));

            log.info("Batch {} started — {} PSV files queued", batchId, foundFiles.size());
            return "Batch Started: " + batchId;

        } catch (Exception e) {
            log.error("Batch initiation failed", e);
            throw new RuntimeException(e);
        }
    }

//    public String initiateBatch(Long configId,String date) {
//        try {
//            AsciiConfig config = configRepo.findById(configId)
//                    .orElseThrow(() -> new RuntimeException("Config not found"));
//
//            FileSystem fs = hdfsService.getFs();
//
//
//            String reportId = config.getReportId();
////            String keyword = reportId.contains("_") ? reportId.split("_")[0] : reportId;
//

    /// /            String inputPathStr=basePath+"/"+date+"/"+keyword+"_report";
//
//            String inputPathStr=basePath+"/"+date+"/"+reportId;
//            Path baseDir=new Path(inputPathStr);
//
//            log.info("Scanning directory: {}", baseDir);
//
//            RemoteIterator<LocatedFileStatus> files = fs.listFiles(baseDir, true);
//
//            List<Path> foundFiles = new ArrayList<>();
//
//            while (files.hasNext()) {
//                Path p = files.next().getPath();
//                log.info("Checking file: {}", p);
//
//                if (p.getName().endsWith(".psv")) {
//                    foundFiles.add(p);
//                }
//            }
//
//            if (foundFiles.isEmpty()) return "No files found";
//
//            String batchId = UUID.randomUUID().toString();
//            batchTracker.put(batchId, new BatchStats(foundFiles.size()));
//
//            for (Path p : foundFiles) {
//                jobQueueManager.submitFileJob(new FileJob(configId, p, batchId,date));
//            }
//
//            return "Batch Started: " + batchId;
//
//        } catch (Exception e) {
//            log.error("Batch initiation failed", e);
//            throw new RuntimeException(e);
//        }
//    }

    // =========================
    // 2. PROCESS FILE
    // =========================
    public void processSingleFile(FileJob job) {

        try {
            FileSystem fs = hdfsService.getFs();
            AsciiConfig config = configRepo.findById(job.getConfigId()).orElseThrow();

            Path inputPath = job.getFilePath();

//            log.info("Processing file: {}", inputPath);

            //  SAFE DATE EXTRACTION (IMPORTANT FIX)
            String date = job.getDate();
            String reportId = config.getReportId();

            Path outputDir = new Path(basePath + "/" + date + "/ascii_files/" + reportId + "/");

            if (!fs.exists(outputDir)) {
                fs.mkdirs(outputDir);
                log.info("Created output dir: {}", outputDir);
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(fs.open(inputPath)));
            // HEADER
            HeaderInfo header = parseHeaderEfficiently(fs, inputPath);
            String branchCode = String.format("%5s", header.branchCode).replace(' ', '0');
            Path outputFile = new Path(outputDir, branchCode + "." + config.getOutputFileName() + "." + header.reportDate + "." + config.getFileType());

            String headerLine = config.getOutputFirstLine() + branchCode + header.reportDate + "F";

            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(fs.create(outputFile)));
            writer.write(headerLine);
            writer.newLine();

            List<String> allLines = new ArrayList<>();
            String line;

            while ((line = reader.readLine()) != null) {
                try {
                    String processed = transformLine(line, config);

                    if (processed != null) {
                        allLines.add(processed);
                    }

                } catch (Exception e) {
                    log.error("Row error: {}", line, e);
                }
            }


            log.info("Sorting {} records.....", allLines.size());

            allLines.sort(Comparator.comparingInt(s -> {
                try {
                    return Integer.parseInt(s.substring(0, 5));
                } catch (Exception e) {
                    return Integer.MAX_VALUE;
                }
            }));

            for (String record : allLines) {
                writer.write(record);
                writer.newLine();
            }

            if (config.getOutputEndLine() != null) {
                writer.write(config.getOutputEndLine());
            }

            reader.close();
            writer.close();

            log.info("File completed: {}", outputFile);

        } catch (Exception e) {
            log.error("Processing failed", e);
        }
    }

    // =========================
    // CORE LOGIC (ORIGINAL RESTORED)
    // =========================
    private String transformLine(String line, AsciiConfig config) {

        String[] columns = line.split("\\|", -1);
        if (!isValidRow(columns, config)) return null;

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

        if (valid) {
            String headPadded = RuleParser.applyHeadPadding(items.get(0).head, config.getOutputHeadColPad());

            return headPadded + sb.toString();
        }

        return null;
    }

    // =========================
    // COLUMN EXTRACTION (DB DRIVEN)
    // =========================
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
        if (idx >= cols.length) return BigDecimal.ZERO;
        return parseAmount(cols[idx]);
    }

    private BigDecimal parseAmount(String val) {
        try {
            if (val == null || val.trim().isEmpty()) return BigDecimal.ZERO;
            return new BigDecimal(val.trim().replace(",", ""));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private boolean isValidRow(String[] cols, AsciiConfig config) {
        int idx = config.getInputHeadCol() - 1;
        if (cols.length <= idx) return false;

        String head = cols[idx].trim();

        Pattern p = INPUT_REGEX_CACHE.computeIfAbsent(config.getInputHeadRegex(), Pattern::compile);

        return p.matcher(head).lookingAt();
    }

    // =========================
    // HEADER
    // =========================
    private HeaderInfo parseHeaderEfficiently(FileSystem fs, Path path) {

        HeaderInfo info = new HeaderInfo();
        info.branchCode = "00000";
        info.reportDate = "";

        Pattern branchPattern = Pattern.compile("(?i)BRANCH\\s*::\\s*(\\d+)");
        Pattern datePattern = Pattern.compile("REPORT DATE\\s*::\\s*(\\S+)");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(fs.open(path)))) {

            String line;
            int count = 0;

            while ((line = reader.readLine()) != null && count++ < 50) {

                Matcher m1 = branchPattern.matcher(line);
                if (m1.find()) info.branchCode = m1.group(1);

                Matcher m2 = datePattern.matcher(line);
                if (m2.find()) info.reportDate = normalizeDate(m2.group(1));
            }

        } catch (Exception e) {
            log.warn("Header parse error", e);
        }

        return info;
    }

    private String normalizeDate(String d) {
        try {
            return LocalDate.parse(d, DateTimeFormatter.ofPattern("yyyy-MM-dd")).format(DateTimeFormatter.ofPattern("ddMMyyyy"));
        } catch (Exception e) {
            return "";
        }
    }

    private static class ProcessingItem {
        String head;
        BigDecimal amount;
        int colIndex;

        ProcessingItem(String h, BigDecimal a, int c) {
            head = h;
            amount = a;
            colIndex = c;
        }
    }

    private static class HeaderInfo {
        String branchCode;
        String reportDate;
    }
}