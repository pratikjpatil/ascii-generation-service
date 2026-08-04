package com.tcs.fincore.AsciiGenerationService.service;

import com.tcs.fincore.AsciiGenerationService.dto.TbAsciiGenerationArtifact;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class TbAsciiGenerationService {

    private static final int MAX_RETRY = 3;
    private static final int FETCH_SIZE = 5000;
    private static final String BRANCH_CODE_PATTERN = "[A-Za-z0-9_-]{1,20}";
    private static final String REPORT_SEGMENT_PATTERN = "[A-Za-z0-9_-]{1,80}";
    private static final String REPORT_CSV_HEADER = "REPT_HEAD,HEAD_DESC,CGL,CGL_DESCRIPTION,CURRENCY,BALANCE,CURRENCY_RATE,EQUI_INR_BALANCE";

    private static final DateTimeFormatter RUN_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static final DateTimeFormatter REPORT_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

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
                                                  String trackingRunId, // Used for logging
                                                  List<String> branchCodes,
                                                  String headerId,
                                                  String footerId,
                                                  String fileIdentifier,
                                                  LocalDate date,
                                                  AtomicBoolean checkedPathTraversal,
                                                  EnumSet<TbAsciiGenerationArtifact> artifacts) {
        validateReportId(reportId);
        validateBranchCodes(branchCodes);
        FileSystem hdfs = hdfsService.getFs();

        if (hdfs == null) {
            throw new TbAsciiGenerationException("HDFS Connection Failed");
        }

        String safeDate = date.toString();
        OutputDirectories dirs = resolveOutputDirectories(reportId, safeDate);
        ensureOutputDirectories(hdfs, dirs, checkedPathTraversal, artifacts, branchCodes);

        StringBuilder errorSb = new StringBuilder();
        int retries = 0;

        while (retries < MAX_RETRY) {
            try {
                // Pass reportId to be used as the DB execution identifier
                generateBatchFiles(hdfs, dirs, fileIdentifier, reportId, trackingRunId, branchCodes, date, headerId, footerId, artifacts, errorSb);
                break;
            } catch (DataAccessException ex) {
                retries++;
                log.error("TB ASCII database failure. reportId={}, trackingRunId={}, branches={}, retry={}/{}", reportId, trackingRunId, branchCodes.size(), retries, MAX_RETRY, ex);
                if (retries >= MAX_RETRY) {
                    throw new TbAsciiGenerationException("DB Connection Failed", ex);
                }
                sleepBeforeRetry(retries);
            }
        }
        return CompletableFuture.completedFuture(toErrorSummary(errorSb));
    }

    private void generateBatchFiles(FileSystem hdfs,
                                    OutputDirectories dirs,
                                    String fileIdentifier,
                                    String reportId,
                                    String trackingRunId,
                                    List<String> branchCodes,
                                    LocalDate date,
                                    String headerId,
                                    String footerId,
                                    EnumSet<TbAsciiGenerationArtifact> artifacts,
                                    StringBuilder errorSb) {
        jdbcTemplate.execute((Connection conn) -> {
            OracleConnection oracleConnection = conn.unwrap(OracleConnection.class);
            java.sql.Array branchArray = null;
            try {
                branchArray = oracleConnection.createOracleArray("TYPE_LIST", branchCodes.toArray(new String[0]));
                String procedureName = generateAsciiProcedureName.trim();
                String sqlCall = "{call " + procedureName + "(?, ?, ?, ?, ?, ?, ?, ?)}";

                try (CallableStatement cstmt = conn.prepareCall(sqlCall)) {
                    cstmt.setString(1, reportId);
                    cstmt.setString(2, reportId);
                    cstmt.setArray(3, branchArray);
                    cstmt.setDate(4, Date.valueOf(date));
                    cstmt.setString(5, headerId);
                    cstmt.setString(6, footerId);
                    cstmt.registerOutParameter(7, Types.REF_CURSOR);
                    cstmt.registerOutParameter(8, Types.REF_CURSOR);

                    cstmt.execute();

                    if (artifacts.contains(TbAsciiGenerationArtifact.TB_ASCII)) {
                        try (ResultSet asciiRs = (ResultSet) cstmt.getObject(7)) {
                            writeAsciiFiles(asciiRs, hdfs, dirs.asciiDir(), fileIdentifier, branchCodes, date, errorSb);
                        }
                    }
                    if (artifacts.contains(TbAsciiGenerationArtifact.TB_ASCII_REPORT)) {
                        try (ResultSet reportRs = (ResultSet) cstmt.getObject(8)) {
                            writeReportFiles(reportRs, hdfs, dirs.reportDir(), fileIdentifier, date, errorSb);
                        }
                    }
                }
            } catch (SQLException ex) {
                appendBranchError(errorSb, branchCodes.size(), branchCodes.toString(), "Database cursor failed: " + ex.getMessage());
                throw ex;
            } finally {
                if (branchArray != null) {
                    branchArray.free();
                }
            }
            return null;
        });
    }

    private void writeAsciiFiles(ResultSet rs, FileSystem hdfs, org.apache.hadoop.fs.Path reportDir, String fileIdentifier,
                                 List<String> branchCodes, LocalDate date, StringBuilder errorSb) throws SQLException {
        rs.setFetchSize(FETCH_SIZE);
        int counter = 0;
        BufferedWriter writer = null;
        String fileName = null;
        boolean writeError = false;
        try {
            while (rs.next()) {
                String line = rs.getString(1);
                if (line == null) continue;
                if (line.trim().endsWith("F")) {
                    closeCurrentWriter(hdfs, reportDir, fileName, writer, writeError, errorSb, currentBranch(branchCodes, counter - 1));
                    writer = null;
                    writeError = false;
                    if (counter >= branchCodes.size()) {
                        appendBranchError(errorSb, 1, "UNKNOWN", "Database returned more branch files than requested");
                        break;
                    }
                    fileName = buildAsciiFileName(branchCodes.get(counter), fileIdentifier, DateTimeFormatter.BASIC_ISO_DATE.format(date));
                    writer = getFileWriter(reportDir, fileName, hdfs);
                    counter++;
                } else if (writer != null && !writeError) {
                    writer.newLine();
                } else {
                    continue;
                }
                if (!writeError) {
                    writer.write(line);
                }
            }
        } catch (IOException ex) {
            writeError = true;
            appendBranchError(errorSb, 1, currentBranch(branchCodes, counter - 1), "ASCII HDFS write failed: " + ex.getMessage());
        } finally {
            closeCurrentWriter(hdfs, reportDir, fileName, writer, writeError, errorSb, currentBranch(branchCodes, counter - 1));
        }
    }

    private void writeReportFiles(ResultSet rs, FileSystem hdfs, org.apache.hadoop.fs.Path reportDir, String fileIdentifier,
                                  LocalDate date, StringBuilder errorSb) throws SQLException {
        rs.setFetchSize(FETCH_SIZE);
        BufferedWriter writer = null;
        String currentBranch = null;
        String fileName = null;
        boolean writeError = false;

        String reportName = resolveReportName(fileIdentifier);
        String reportDate = date.format(REPORT_DATE_FORMATTER);

        try {
            while (rs.next()) {
                String branchCode = rs.getString("BRANCH_CODE");
                if (branchCode == null || !branchCode.equals(currentBranch)) {
                    closeCurrentWriter(hdfs, reportDir, fileName, writer, writeError, errorSb, currentBranch == null ? "UNKNOWN" : currentBranch);
                    writer = null;
                    writeError = false;
                    currentBranch = branchCode;
                    fileName = buildReportFileName(branchCode, fileIdentifier, DateTimeFormatter.BASIC_ISO_DATE.format(date));
                    writer = getFileWriter(reportDir, fileName, hdfs);

                    writer.write(",,,State Bank of India");
                    writer.newLine();
                    writer.newLine();

                    writer.write("Report Name," + csv(reportName));
                    writer.newLine();
                    writer.write("Report Date," + reportDate);
                    writer.newLine();
                    writer.write("Run Date," + LocalDateTime.now().format(RUN_DATE_FORMATTER));
                    writer.newLine();
                    writer.write("Branch Code," + csv(branchCode));
                    writer.newLine();

                    writer.newLine();

                    writer.write(REPORT_CSV_HEADER);
                }
                writer.newLine();
                writer.write(toCsvRow(rs));
            }
        } catch (IOException ex) {
            writeError = true;
            appendBranchError(errorSb, 1, currentBranch == null ? "UNKNOWN" : currentBranch, "Report HDFS write failed: " + ex.getMessage());
        } finally {
            closeCurrentWriter(hdfs, reportDir, fileName, writer, writeError, errorSb, currentBranch == null ? "UNKNOWN" : currentBranch);
        }
    }

    private String resolveReportName(String fileIdentifier) {
        if (fileIdentifier == null) return "Unknown Trial Balance Report";
        return switch (fileIdentifier) {
            case "TBY" -> "YSA Trial Balance Report";
            case "TBNWSA" -> "NWSA Trial Balance Report";
            case "TBP" -> "PNL Trial Balance Report";
            default -> fileIdentifier + " Trial Balance Report";
        };
    }

    private String toCsvRow(ResultSet rs) throws SQLException {
        return csv(rs.getString("REPT_HEAD")) + ','
                + csv(rs.getString("HEAD_DESC")) + ','
                + csv(rs.getString("CGL")) + ','
                + csv(rs.getString("CGL_DESCRIPTION")) + ','
                + csv(rs.getString("CURRENCY")) + ','
                + csv(rs.getString("BALANCE")) + ','
                + csv(rs.getString("CURRENCY_RATE")) + ','
                + csv(rs.getString("EQUI_INR_BALANCE"));
    }

    private String csv(String value) {
        if (value == null) return "";
        String escaped = value.replace("\"", "\"\"");
        return (escaped.contains(",") || escaped.contains("\n") || escaped.contains("\r") || escaped.contains("\"")) ? "\"" + escaped + "\"" : escaped;
    }

    private OutputDirectories resolveOutputDirectories(String reportId, String safeDate) {
        String reportSegment = reportId.toLowerCase();
        if (!reportSegment.matches(REPORT_SEGMENT_PATTERN)) {
            throw new PathValidationException("Invalid report id segment for output path: " + reportSegment);
        }
        return new OutputDirectories(
                new org.apache.hadoop.fs.Path(basePath + "/" + safeDate + "/tb_ascii_files/" + reportSegment),
                new org.apache.hadoop.fs.Path(basePath + "/" + safeDate + "/tb_ascii_report_files/" + reportSegment)
        );
    }

    private void ensureOutputDirectories(FileSystem hdfs, OutputDirectories dirs, AtomicBoolean checkedPathTraversal,
                                         EnumSet<TbAsciiGenerationArtifact> artifacts, List<String> branchCodes) {
        if (!checkedPathTraversal.compareAndSet(false, true)) return;
        try {
            if (artifacts.contains(TbAsciiGenerationArtifact.TB_ASCII)) ensureDirectory(hdfs, dirs.asciiDir(), "/tb_ascii_files/");
            if (artifacts.contains(TbAsciiGenerationArtifact.TB_ASCII_REPORT)) ensureDirectory(hdfs, dirs.reportDir(), "/tb_ascii_report_files/");
        } catch (IOException ex) {
            log.error("TB ASCII path creation failed. branchCodes={}", branchCodes, ex);
            throw new PathValidationException("Unable to create TB ASCII output directory", ex);
        }
    }

    private void ensureDirectory(FileSystem hdfs, org.apache.hadoop.fs.Path dir, String marker) throws IOException {
        String normalized = dir.toUri().normalize().getPath();
        if (normalized == null || !normalized.contains(marker)) {
            throw new PathValidationException("Path traversal detected for TB ASCII output directory");
        }
        if (!hdfs.exists(dir)) hdfs.mkdirs(dir);
    }

    private void validateReportId(String reportId) { if (reportId == null || !reportId.matches(REPORT_SEGMENT_PATTERN)) throw new PathValidationException("Invalid report id for TB ASCII output file: " + reportId); }
    private void validateBranchCodes(List<String> branchCodes) { for (String b : branchCodes) if (b == null || !b.matches(BRANCH_CODE_PATTERN)) throw new PathValidationException("Invalid branch code for TB ASCII output file: " + b); }
    private String buildAsciiFileName(String branchCode, String identifier, String safeDate) { return String.format("%s.%s.%s", branchCode, identifier, safeDate); }
    private String buildReportFileName(String branchCode, String identifier, String safeDate) { return String.format("%s.%s.%s_report.csv", branchCode, identifier, safeDate); }

    private BufferedWriter getFileWriter(org.apache.hadoop.fs.Path reportDir, String fileName, FileSystem hdfs) throws IOException {
        if (!hdfs.exists(reportDir)) hdfs.mkdirs(reportDir);
        FSDataOutputStream out = hdfs.create(new org.apache.hadoop.fs.Path(reportDir, fileName), true);
        return new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 1024 * 1024);
    }

    private void closeCurrentWriter(FileSystem hdfs, org.apache.hadoop.fs.Path reportDir, String fileName, BufferedWriter writer, boolean deleteFile, StringBuilder errorSb, String branchCode) {
        if (writer == null) return;
        try {
            writer.close();
            if (deleteFile && fileName != null) hdfs.delete(new org.apache.hadoop.fs.Path(reportDir, fileName), false);
        } catch (IOException ex) {
            appendBranchError(errorSb, 1, branchCode, "Unable to close writer: " + ex.getMessage());
            log.error("Failed closing TB ASCII writer. fileName={}, branchCode={}", fileName, branchCode, ex);
        }
    }

    private void appendBranchError(StringBuilder errorSb, int count, String branchCode, String reason) { errorSb.append(count).append("|Error processing TB ASCII for branchCode(s): ").append(branchCode).append(", Reason: ").append(reason).append(System.lineSeparator()); }
    private String toErrorSummary(StringBuilder errorSb) { String e = errorSb.toString().trim(); if (e.isEmpty()) return null; AtomicInteger errors = new AtomicInteger(); Arrays.stream(e.split("\\R")).forEach(line -> errors.addAndGet(Integer.parseInt(line.split("\\|", 2)[0]))); return errors + System.lineSeparator() + e; }
    private String currentBranch(List<String> branchCodes, int index) { return index < 0 || index >= branchCodes.size() ? "UNKNOWN" : branchCodes.get(index); }
    private void sleepBeforeRetry(int retries) { try { Thread.sleep(1000L * (long) Math.pow(2, retries)); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new TbAsciiGenerationException("DB Connection Failed", ex); } }

    private record OutputDirectories(org.apache.hadoop.fs.Path asciiDir, org.apache.hadoop.fs.Path reportDir) {}
}