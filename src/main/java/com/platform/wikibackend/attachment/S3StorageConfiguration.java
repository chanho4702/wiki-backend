package com.platform.wikibackend.attachment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "platform.wiki.storage.s3", name = "enabled", havingValue = "true")
public class S3StorageConfiguration {

    @Bean(destroyMethod = "close")
    S3Client wikiAttachmentS3Client(
            @Value("${platform.wiki.storage.s3.region}") String region,
            @Value("${platform.wiki.storage.s3.endpoint:}") String endpoint,
            @Value("${platform.wiki.storage.s3.path-style-access:false}") boolean pathStyleAccess,
            @Value("${platform.wiki.storage.s3.access-key:}") String accessKey,
            @Value("${platform.wiki.storage.s3.secret-key:}") String secretKey) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyleAccess)
                        .build());
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        if (!accessKey.isBlank() || !secretKey.isBlank()) {
            if (accessKey.isBlank() || secretKey.isBlank()) {
                throw new IllegalArgumentException("S3 access-key와 secret-key는 함께 설정해야 합니다");
            }
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        }
        return builder.build();
    }

    @Bean
    S3AttachmentStorage s3AttachmentStorage(
            S3Client wikiAttachmentS3Client,
            @Value("${platform.wiki.storage.s3.bucket}") String bucket) {
        return new S3AttachmentStorage(wikiAttachmentS3Client, bucket);
    }
}
