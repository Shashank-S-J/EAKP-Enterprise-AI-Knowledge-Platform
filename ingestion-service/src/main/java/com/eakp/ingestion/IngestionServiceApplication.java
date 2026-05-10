package com.eakp.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.eakp.ingestion", "com.eakp.common"})
@EnableAsync
@EnableScheduling
public class IngestionServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IngestionServiceApplication.class, args);
    }
}
