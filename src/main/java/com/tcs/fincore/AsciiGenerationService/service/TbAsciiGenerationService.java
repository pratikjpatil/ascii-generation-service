package com.tcs.fincore.AsciiGenerationService.service;

import com.tcs.fincore.AsciiGenerationService.exception.PathValidationException;
import com.tcs.fincore.AsciiGenerationService.exception.TbAsciiGenerationException;
import lombok.extern.slf4j.Slf4j;
import oracle.jdbc.OracleConnection;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@Slf4j
public class TbAsciiGenerationService {
    private static final int MAX_RETRY = 3;
    private static final String BRANCH_CODE_PATTERN = "[A-Za-z0-9_-]{1,20}";
    private static final String REPORT_SEGMENT_PATTERN = "[A-Za-z0-9_-]{1,80}";

    @Value("${procedure.ascii.generate}")
    private String generateAsciiProcedureName;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    HdfsService hdfsService;

    @Value("${app.input.base.path}")
    private String basePath;

    @Async("tbTaskExecutor")
    public CompletableFuture<String> processBatch(String reportId,
                                                  String runId,
                                                  List<String> branchCodes,
//                                                  String dateStr,
                                                  String headerId,
                                                  String footerId,
                                                  String fileIdentifier,
                                                  LocalDate date,
                                                  AtomicBoolean checkedPathTraversal) {
        validateReportId(reportId);
        validateBranchCodes(branchCodes);
        FileSystem hdfs = hdfsService.getFs();
        if (hdfs == null) {
            throw new TbAsciiGenerationException("HDFS Connection Failed");
        }

        String safeDate = date.toString();
        org.apache.hadoop.fs.Path reportDir = resolveReportDirectory(reportId, safeDate);
        ensureReportDirectory(hdfs, reportDir, checkedPathTraversal, branchCodes);

        StringBuilder errorSb = new StringBuilder();
        int retries = 0;
        while (retries < MAX_RETRY) {
            try {
                generateBatchFiles(hdfs, reportDir, fileIdentifier, runId, branchCodes, date, headerId, footerId, safeDate, errorSb);
                break;
            } catch (DataAccessException ex) {
                retries++;
                log.error("TB ASCII database failure. reportId={}, runId={}, branches={}, retry={}/{}",
                        reportId, runId, branchCodes.size(), retries, MAX_RETRY, ex);
                if (retries >= MAX_RETRY) {
                    throw new TbAsciiGenerationException("DB Connection Failed", ex);
                }
                sleepBeforeRetry(retries);
            }
        }

        return CompletableFuture.completedFuture(toErrorSummary(errorSb));
    }

    private void generateBatchFiles(FileSystem hdfs,
                                    org.apache.hadoop.fs.Path reportDir,
                                    String fileIdentifier,
                                    String runId,
                                    List<String> branchCodes,
                                    LocalDate date,
                                    String headerId,
                                    String footerId,
                                    String safeDate,
                                    StringBuilder errorSb) {
        jdbcTemplate.execute((Connection conn) -> {
            OracleConnection oracleConnection = conn.unwrap(OracleConnection.class);
            java.sql.Array branchArray = null;
            int counter = 0;
            BufferedWriter fileWriter = null;
            String fileName = null;
            boolean fileHadWriteError = false;
            try {
                branchArray = oracleConnection.createOracleArray("TYPE_LIST", branchCodes.toArray(new String[0]));
                try (CallableStatement cstmt = conn.prepareCall("{call "+generateAsciiProcedureName+"(?, ?, ?, ?,?,?)}")) {
                    cstmt.setString(1, runId);
                    cstmt.setArray(2, branchArray);
                    cstmt.setDate(3, Date.valueOf(date));
                    cstmt.setString(4, headerId);
                    cstmt.setString(5, footerId);
                    cstmt.registerOutParameter(6, Types.REF_CURSOR);
                    cstmt.execute();

                    try (ResultSet rs = (ResultSet) cstmt.getObject(6)) {
                        rs.setFetchSize(1000);
                        while (rs.next()) {
                            String line = rs.getString(1);
                            if (line == null) {
                                continue;
                            }
                            if (line.trim().endsWith("F")) {
                                closeCurrentWriter(hdfs, reportDir, fileName, fileWriter, fileHadWriteError, errorSb, currentBranch(branchCodes, counter - 1));
                                fileHadWriteError = false;
                                if (counter >= branchCodes.size()) {
                                    appendBranchError(errorSb, 1, "UNKNOWN", "Database returned more branch files than requested");
                                    break;
                                }
                                fileName = buildFileName( branchCodes.get(counter),fileIdentifier,DateTimeFormatter.BASIC_ISO_DATE.format(date));
                                fileWriter = getFileWriter(reportDir, fileName, hdfs);
                                counter++;
                            } else if (fileWriter != null && !fileHadWriteError) {
                                fileWriter.newLine();
                            } else {
                                continue;
                            }
                            if (!fileHadWriteError) {
                                try {
                                    fileWriter.write(line);
                                } catch (IOException ex) {
                                    fileHadWriteError = true;
                                    appendBranchError(errorSb, 1, currentBranch(branchCodes, counter - 1), "I/O write failed: " + ex.getMessage());
                                }
                            }
                        }
                    }
                }
            } catch (SQLException ex) {
                closeWriterAfterSqlFailure(hdfs, reportDir, fileName, fileWriter);
                List<String> failedBranches = branchCodes.subList(Math.max(counter, 0), branchCodes.size());
                appendBranchError(errorSb, failedBranches.size(), failedBranches.toString(), "Database cursor failed: " + ex.getMessage());
                throw ex;
            } catch (IOException ex) {
                closeWriterAfterSqlFailure(hdfs, reportDir, fileName, fileWriter);
                List<String> failedBranches = branchCodes.subList(Math.max(counter - 1, 0), branchCodes.size());
                appendBranchError(errorSb, failedBranches.size(), failedBranches.toString(), "HDFS write failed: " + ex.getMessage());
            } finally {
                closeCurrentWriter(hdfs, reportDir, fileName, fileWriter, fileHadWriteError, errorSb, currentBranch(branchCodes, counter - 1));
                if (branchArray != null) {
                    branchArray.free();
                }
//                log.info("TB ASCII batch cursor consumed. reportId={}, requestedBranches={}, processedBranches={}", fileIdentifier, branchCodes.size(), counter);
            }
            return null;
        });
    }

    private org.apache.hadoop.fs.Path resolveReportDirectory(String reportId, String safeDate) {
        String reportSegment = reportId.toLowerCase();
        if (!reportSegment.matches(REPORT_SEGMENT_PATTERN)) {
            throw new PathValidationException("Invalid report id segment for output path: " + reportSegment);
        }
        return new org.apache.hadoop.fs.Path(basePath + "/" + safeDate + "/tb_ascii_files/" + reportSegment);
    }

    private void ensureReportDirectory(FileSystem hdfs,
                                       org.apache.hadoop.fs.Path reportDir,
                                       AtomicBoolean checkedPathTraversal,
                                       List<String> branchCodes) {
        if (checkedPathTraversal.compareAndSet(false, true)) {
            try {
                String normalized = reportDir.toUri().normalize().getPath();
                String expectedPrefix = new org.apache.hadoop.fs.Path(basePath + "/" + reportDir.getParent().getParent().getName() + "/tb_ascii_files/").toUri().normalize().getPath();
                if (normalized == null || expectedPrefix == null || !normalized.startsWith(expectedPrefix)) {
                    throw new PathValidationException("Path traversal detected for TB ASCII output directory");
                }
                if (!hdfs.exists(reportDir)) {
                    hdfs.mkdirs(reportDir);
                    log.info("Created TB ASCII output directory. path={}", reportDir);
                }
            } catch (IOException ex) {
                log.error("TB ASCII path creation failed. branchCodes={}", branchCodes, ex);
                throw new PathValidationException("Unable to create TB ASCII output directory", ex);
            }
        }
    }

    private void validateReportId(String reportId) {
        if (reportId == null || !reportId.matches(REPORT_SEGMENT_PATTERN)) {
            throw new PathValidationException("Invalid report id for TB ASCII output file: " + reportId);
        }
    }

    private void validateBranchCodes(List<String> branchCodes) {
        for (String branchCode : branchCodes) {
            if (branchCode == null || !branchCode.matches(BRANCH_CODE_PATTERN)) {
                throw new PathValidationException("Invalid branch code for TB ASCII output file: " + branchCode);
            }
        }
    }

    private String buildFileName(String branchCode,String identifier, String safeDate) {
        return String.format("%s.%s.%s",branchCode,identifier, safeDate);
    }

    private BufferedWriter getFileWriter(org.apache.hadoop.fs.Path reportDir, String fileName, FileSystem hdfs) throws IOException {
        if (!hdfs.exists(reportDir)) {
            hdfs.mkdirs(reportDir);
            log.info("Created TB ASCII output directory. path={}", reportDir);
        }
        org.apache.hadoop.fs.Path targetPath = new org.apache.hadoop.fs.Path(reportDir, fileName);
        FSDataOutputStream out = hdfs.create(targetPath, true);
        return new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
    }

    private void closeCurrentWriter(FileSystem hdfs,
                                    org.apache.hadoop.fs.Path reportDir,
                                    String fileName,
                                    BufferedWriter fileWriter,
                                    boolean deleteFile,
                                    StringBuilder errorSb,
                                    String branchCode) {
        if (fileWriter == null) {
            return;
        }
        try {
            fileWriter.close();
            if (deleteFile && fileName != null) {
                hdfs.delete(new org.apache.hadoop.fs.Path(reportDir, fileName), false);
            }
        } catch (IOException ex) {
            appendBranchError(errorSb, 1, branchCode, "Unable to close writer: " + ex.getMessage());
            log.error("Failed closing TB ASCII writer. fileName={}, branchCode={}", fileName, branchCode, ex);
        }
    }

    private void closeWriterAfterSqlFailure(FileSystem hdfs, org.apache.hadoop.fs.Path reportDir, String fileName, BufferedWriter fileWriter) {
        if (fileWriter == null) {
            return;
        }
        try {
            fileWriter.close();
            if (fileName != null) {
                hdfs.delete(new org.apache.hadoop.fs.Path(reportDir, fileName), false);
            }
        } catch (IOException ex) {
            log.error("Failed cleaning partial TB ASCII file after failure. fileName={}", fileName, ex);
        }
    }

    private void appendBranchError(StringBuilder errorSb, int count, String branchCode, String reason) {
        errorSb.append(count)
                .append("|Error processing TB ASCII for branchCode(s): ")
                .append(branchCode)
                .append(", Reason: ")
                .append(reason)
                .append(System.lineSeparator());
    }

    private String toErrorSummary(StringBuilder errorSb) {
        String errorString = errorSb.toString().trim();
        if (errorString.isEmpty()) {
            return null;
        }
        AtomicInteger errors = new AtomicInteger();
        Arrays.stream(errorString.split("\\R")).forEach(line -> {
            String[] md = line.split("\\|", 2);
            errors.addAndGet(Integer.parseInt(md[0]));
        });
        return errors + System.lineSeparator() + errorString;
    }

    private String currentBranch(List<String> branchCodes, int index) {
        if (index < 0 || index >= branchCodes.size()) {
            return "UNKNOWN";
        }
        return branchCodes.get(index);
    }

    private void sleepBeforeRetry(int retries) {
        try {
            Thread.sleep(1000L * (long) Math.pow(2, retries));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TbAsciiGenerationException("DB Connection Failed", ex);
        }
    }
}