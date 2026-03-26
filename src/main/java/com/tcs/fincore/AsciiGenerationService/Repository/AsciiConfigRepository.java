package com.tcs.fincore.AsciiGenerationService.Repository;


import com.tcs.fincore.AsciiGenerationService.Model.AsciiConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AsciiConfigRepository extends JpaRepository<AsciiConfig, Long> {
}