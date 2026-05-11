package com.eakp.ingestion.config;

import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration
@Slf4j
public class StorageConfig {

    @Value("${app.storage.endpoint}")
    private String endpoint;

    @Value("${app.storage.access-key}")
    private String accessKey;

    @Value("${app.storage.secret-key}")
    private String secretKey;

    @Value("${app.storage.region:us-east-1}")
    private String region;

    @Bean
    public MinioClient minioClient() {
        log.info("Connecting to S3-compatible storage at {}", endpoint);

        URI uri = URI.create(endpoint);
        String baseUrl = uri.getScheme() + "://" + uri.getHost()
                + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String pathPrefix = uri.getPath(); // e.g. "/storage/v1/s3"

        MinioClient.Builder builder = MinioClient.builder()
                .credentials(accessKey, secretKey)
                .region(region);

        if (pathPrefix != null && !pathPrefix.isEmpty() && !pathPrefix.equals("/")) {
            // Supabase-style S3: endpoint has a sub-path like /storage/v1/s3
            // Use an OkHttp interceptor to prepend the path to every request
            OkHttpClient httpClient = new OkHttpClient.Builder()
                    .addInterceptor(chain -> {
                        HttpUrl original = chain.request().url();
                        // Prepend the path prefix before the S3 path
                        String newPath = pathPrefix + original.encodedPath();
                        HttpUrl newUrl = original.newBuilder()
                                .encodedPath(newPath)
                                .build();
                        return chain.proceed(
                                chain.request().newBuilder().url(newUrl).build()
                        );
                    })
                    .build();
            builder.endpoint(baseUrl).httpClient(httpClient);
        } else {
            builder.endpoint(baseUrl);
        }

        return builder.build();
    }
}