package com.tcs.fincore.AsciiGenerationService.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
public class TbAsciiService {

    private static final Logger log = LoggerFactory.getLogger(TbAsciiService.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
            log.error("[TB] Invalid Date: {}", dateStr);
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
}