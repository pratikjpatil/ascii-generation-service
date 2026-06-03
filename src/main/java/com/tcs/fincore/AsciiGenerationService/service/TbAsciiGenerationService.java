package com.tcs.fincore.AsciiGenerationService.service;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.time.Instant;
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
    private final int MAXRETRY = 3;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    HdfsService hdfsService;

    @Value("${app.input.base.path}")
    private String basePath;

    @Async("tbTaskExecutor")
    public CompletableFuture<Void> performTask(String reportId, String runId, String branchCode, String dateStr, String headerId, String footerId, String outputDir) {
        LocalDate date;
//      System.out.println("fffff");
        try {
            date = LocalDate.parse(dateStr);
        } catch (Exception e) {
            log.error("[2TB] Invalid Date: {}", dateStr);
            return CompletableFuture.completedFuture(null);
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
            return CompletableFuture.completedFuture(null);
        }

        //log.info("[TB] Generating: {}", targetPath);


        // 3. Generate
        jdbcTemplate.execute((Connection conn) -> {
            log.info("started: {}", Instant.now());
            try (CallableStatement cstmt = conn.prepareCall("{call SP_GENERATE_TB_ASCII_STREAM_BULK(?, ?, ?, ?,?,?)}")) {
                cstmt.setString(1, runId);
                cstmt.setString(2, branchCode);
                cstmt.setDate(3, Date.valueOf(date));
                cstmt.setString(4, headerId);
                cstmt.setString(5, footerId);
                cstmt.registerOutParameter(6, Types.REF_CURSOR);
                cstmt.execute();

                try (ResultSet rs = (ResultSet) cstmt.getObject(6);
                     BufferedWriter writer = Files.newBufferedWriter(targetPath, StandardCharsets.UTF_8)) {
                    while (rs.next()) {
                        String line = rs.getString(1);
                        if (line != null) {
                            writer.write(line);
                            writer.newLine();
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                //log.info("[TB] DONE: {}", fileName);
            } catch (DataAccessException e) {
                log.error("[TB] DB Error");
            }
            return null;
        });

        return CompletableFuture.completedFuture(null);
    }

    @Async("tbTaskExecutor")
    public CompletableFuture<String> processBatch(String reportId,
                                                  String runId,
                                                  List<String> branchCodes,
                                                  String dateStr,
                                                  String headerId,
                                                  String footerId,
                                                  LocalDate date,
                                                  AtomicBoolean checkedPathTraversal) {
        /* error list*/
        StringBuilder errorSb = new StringBuilder();
//
//        /* Hadoop Connection check */
//        int retries = 3;
//        int currentRetries = 0;
//        FileSystem hdfs = hdfsService.getFs();
//        while (hdfs == null) {
//            if (currentRetries == retries) {
////                break;
//                throw new RuntimeException("HDFS Connection Failed!");
//            }
//            hdfs = hdfsService.getFs();
//            currentRetries++;
//        }
        FileSystem hdfsFinal = hdfsService.getFs();
//        log.info("Hadoop connection established!");

        String safeDate = date.format(DateTimeFormatter.BASIC_ISO_DATE); // YYYYMMDD
        org.apache.hadoop.fs.Path reportDir = new org.apache.hadoop.fs.Path(basePath + "/" + safeDate + "/tb_ascii_files/" + reportId.split("_")[0].toLowerCase());

        if (!checkedPathTraversal.get()) {
            try {
                String prefix = basePath + "/" + safeDate + "/tb_ascii_files/";
                if (!reportDir.toUri().normalize().toString().startsWith(prefix)) {
                    throw new RuntimeException("Path Traversal detected!");
                }
                if (!hdfsFinal.exists(reportDir)) {
                    hdfsFinal.mkdirs(reportDir);
                    log.info("Created output dir: {}", reportDir);
                }
            } catch (Exception e) {
                log.error("[TB] Path Error for {}: {}", branchCodes, e.getMessage());
                String err = "10" + "|Error Processing batch of batch ids : " + branchCodes.toString() + ", Reason: " + e.getMessage();
                return CompletableFuture.completedFuture(err);
            }
        }


        // 3. Generating TB ASCII
        int retries = 0;
        while (retries <= MAXRETRY) {
            try {
                jdbcTemplate.execute((Connection conn) -> {
                    OracleConnection connection = conn.unwrap(OracleConnection.class);
//                    log.info("started TB ASCII generation for codes: {}", branchCodes.toString());
                    int counter = 0;
                    BufferedWriter fileWriter = null;
                    String fileName = null;

                    try (CallableStatement cstmt = conn.prepareCall("{call SP_GENERATE_TB_ASCII_STREAM_NEW1(?, ?, ?, ?,?,?)}")) {
                        cstmt.setString(1, runId);
                        cstmt.setArray(2, connection.createOracleArray("TYPE_LIST", branchCodes.toArray(new String[0])));
                        cstmt.setDate(3, Date.valueOf(date));
                        cstmt.setString(4, headerId);
                        cstmt.setString(5, footerId);
                        cstmt.registerOutParameter(6, Types.REF_CURSOR);
                        cstmt.execute();

                        try (ResultSet rs = (ResultSet) cstmt.getObject(6)) {
                            rs.setFetchSize(1000);
//                     BufferedWriter writer = Files.newBufferedWriter(targetPath, StandardCharsets.UTF_8))
                            boolean exceptionFlag = false;
                            while (rs.next()) {
                                try {
                                    String line = rs.getString(1);
                                    if (line != null) {

//                                    if (fileWriter == null && counter == 0) {
//                                        fileName = String.format("%s_%s_%s.txt", reportId, branchCodes.get(counter), safeDate);
//                                        fileWriter = getFileWriter(reportDir, fileName, hdfsFinal);
//                                    }
//                                    if (fileWriter == null) {
//                                        continue;
//                                    }
                                        if (line.trim().endsWith("F")) {
                                            if (counter == 0) {
                                                fileName = String.format("%s_%s_%s.txt", reportId, branchCodes.get(counter++), safeDate);
                                                fileWriter = getFileWriter(reportDir, fileName, hdfsFinal);
                                            } else {
//                                                fileWriter.flush();
                                                fileWriter.close();
                                                if (exceptionFlag) {
                                                    hdfsFinal.delete(new org.apache.hadoop.fs.Path(reportDir, fileName), false);
                                                    exceptionFlag = false;
                                                }
                                                fileName = String.format("%s_%s_%s.txt", reportId, branchCodes.get(counter++), safeDate);
                                                fileWriter = getFileWriter(reportDir, fileName, hdfsFinal);
                                            }
                                        } else {
                                            if (fileWriter != null && !exceptionFlag) {
                                                fileWriter.newLine();
                                            } else {
                                                continue;
                                            }
                                        }
                                        fileWriter.write(line);
                                    }
                                } catch (IOException ex) {
                                    ex.printStackTrace();
                                    exceptionFlag = true;
//                                  counter++;
                                    errorSb.append("1|Error Processing file for branchCode: ").append(branchCodes.get(counter - 1)).append(", Reason: ").append(ex.getMessage()).append(System.lineSeparator());
                                }
                            }
                            try {
                                if (fileWriter != null) {
//                                    fileWriter.flush();
                                    fileWriter.close();
                                    if (exceptionFlag) {
                                        hdfsFinal.delete(new org.apache.hadoop.fs.Path(reportDir, fileName), false);
                                    }
                                }
                            } catch (IOException ex) {
                                ex.printStackTrace();
                                errorSb.append("1|Error Processing file for branchCode: ").append(branchCodes.get(counter - 1)).append(", Reason: ").append(ex.getMessage()).append(System.lineSeparator());
                            }
                        }
                    } catch (SQLException ex) {
                        ex.printStackTrace();
                        try {
                            if (fileWriter != null) {
//                                fileWriter.flush();
                                fileWriter.close();
                                hdfsFinal.delete(new org.apache.hadoop.fs.Path(reportDir, fileName), false);
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
//                        errorSb.append("1|Error Processing file for branchCode: " + branchCodes.get(counter) + ", Reason: " + ex.getMessage() + System.lineSeparator());
                        }
                        List<String> subList = branchCodes.subList(counter, branchCodes.size());
                        errorSb.append(subList.size()).append("|Error Processing batch of batch ids : ").append(subList).append(", Reason: ").append(ex.getMessage()).append(System.lineSeparator());
                    }
                    log.info(String.format("%d", counter));
                    return null;
                });
            } catch (DataAccessException ex) {
                retries++;
                try {
                    Thread.sleep(1000 * (int) Math.pow(2, retries));
                } catch (InterruptedException e) {
                    throw new RuntimeException("DB Connection Failed!");
                }
                if (retries == MAXRETRY) {
                    throw new RuntimeException("DB Connection Failed!");
                }
                continue;
//
//            errorSb.append("10" + "|Error Processing batch of batch ids : " + branchCodes.toString() + ", Reason: " + ex.getMessage() + System.lineSeparator());
            }
            break;
        }


        /* closing the established hdfs connection */
//        try {
//            hdfs.close();
//            log.info("Successfully closed HDFS FS instance!");
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }

        String errorString = errorSb.toString().trim();
        log.info(errorString);

        if (!errorString.isEmpty()) {
            AtomicInteger errors = new AtomicInteger();
            String finalErrorString = Arrays.stream(errorString.split("\\R")).map((line) -> {
                String[] md = line.split("\\|");
                errors.addAndGet(Integer.parseInt(md[0]));
                return md[1];
            }).collect(Collectors.joining(""));
            errorString = errors.toString() + System.lineSeparator() + errorString;
        }
        return CompletableFuture.completedFuture(errorString.trim().isEmpty() ? null : errorString);

    }


    /* helper function to switch the fileWriter Reference*/
    private BufferedWriter getFileWriter(org.apache.hadoop.fs.Path reportDir,
//                                         String reportId,
//                                         String branchCode,
//                                         String safeDate,
                                         String fileName,
                                         FileSystem hdfs
    ) throws IOException {
//        String fileName = String.format("%s_%s_%s.txt", reportId, branchCode, safeDate);
        org.apache.hadoop.fs.Path targetPath;
        BufferedWriter writer = null;
//        FileSystem hdfs = null;
//        hdfs = hdfsService.getFs();
        /* Trying to create file on local server when HDFS connection is not establishing */
//        if (hdfs == null) {
//            log.info("HDFS Connection failed! Failover Strategy called!");
//            Path path = Paths.get("E:/Reports/TB_ASCII").normalize();
//            if (!Files.exists(path)) {
//                Files.createDirectory(path);
//            }
//            Path destPath = path.resolve(fileName).normalize();
//            writer = Files.newBufferedWriter(destPath);
//            return writer;
//        }
        if (!hdfs.exists(reportDir)) {
            hdfs.mkdirs(reportDir);
            log.info("Created output dir: {}", reportDir);
        }

        targetPath = new org.apache.hadoop.fs.Path(reportDir, fileName);
        FSDataOutputStream out = null;
//        try {
        out = hdfs.create(targetPath);
//        } catch (IOException e) {
//            log.info("File not created in hadoop!");
//            /*  Error handling required */
//            return null;
//        }
        writer = new BufferedWriter(new OutputStreamWriter(out));
        return writer;
    }
}