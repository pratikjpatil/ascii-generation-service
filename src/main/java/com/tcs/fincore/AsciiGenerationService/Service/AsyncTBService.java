package com.tcs.fincore.AsciiGenerationService.Service;

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
import java.util.concurrent.CompletableFuture;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;


@Service
@Slf4j
public class AsyncTBService {
	
	@Autowired
	JdbcTemplate jdbcTemplate;
	
	@Async("tbTaskExecutor")
	public CompletableFuture<Void> performTask(String reportId,String runId, String branchCode, String dateStr, String headerId, String footerId, String outputDir) {
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

      log.info("[TB] Generating: {}", targetPath);


      // 3. Generate
      jdbcTemplate.execute((Connection conn) -> {
          try (CallableStatement cstmt = conn.prepareCall("{call SP_GENERATE_TB_ASCII_STREAM_NEW(?, ?, ?, ?,?,?)}")) {
              cstmt.setString(1, runId);
              cstmt.setString(2, branchCode);
              cstmt.setDate(3, java.sql.Date.valueOf(date));
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
              }
              log.info("[TB] DONE: {}", fileName);
          } catch (Exception e) {
              log.error("[TB] DB Error {}: {}", fileName, e.getMessage());
          }
          return null;
      });
      
      return CompletableFuture.completedFuture(null) ;
	}
}