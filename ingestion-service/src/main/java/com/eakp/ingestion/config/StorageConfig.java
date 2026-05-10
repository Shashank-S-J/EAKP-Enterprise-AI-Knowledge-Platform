package com.eakp.ingestion.config;

import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class StorageConfig {

    @Value("${app.storage.endpoint}")
    private String endpoint;

    @Value("${app.storage.access-key}")
    private String accessKey;

    @Value("${app.storage.secret-key}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        log.info("Connecting to MinIO at {}", endpoint);
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
