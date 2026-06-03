package com.tcs.fincore.AsciiGenerationService.repository;


import com.tcs.fincore.AsciiGenerationService.model.AsciiConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AsciiConfigRepository extends JpaRepository<AsciiConfig, Long> {
}