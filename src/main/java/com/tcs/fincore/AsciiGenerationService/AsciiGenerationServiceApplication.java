package com.tcs.fincore.AsciiGenerationService;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AsciiGenerationServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(AsciiGenerationServiceApplication.class, args);
	}

}