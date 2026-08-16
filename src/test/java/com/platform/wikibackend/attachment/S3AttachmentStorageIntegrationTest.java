package com.platform.wikibackend.attachment;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

import java.io.ByteArrayInputStream;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class S3AttachmentStorageIntegrationTest {

    private static final String BUCKET = "wiki-integration";

    @Container
    static final GenericContainer<?> S3MOCK = new GenericContainer<>(DockerImageName.parse("adobe/s3mock:5.1.0"))
            .withExposedPorts(9090)
            .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", BUCKET);

    @Test
    void 실제_S3_API로_버전_객체를_저장_조회_삭제한다() throws Exception {
        try (S3Client client = S3Client.builder()
                .endpointOverride(URI.create("http://" + S3MOCK.getHost() + ":" + S3MOCK.getMappedPort(9090)))
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            client.putBucketVersioning(PutBucketVersioningRequest.builder()
                    .bucket(BUCKET)
                    .versioningConfiguration(VersioningConfiguration.builder()
                            .status(BucketVersioningStatus.ENABLED)
                            .build())
                    .build());
            S3AttachmentStorage storage = new S3AttachmentStorage(client, BUCKET);
            byte[] bytes = new byte[]{1, 2, 3, 4};

            StoredObject stored = storage.store(new ByteArrayInputStream(bytes), bytes.length, "image/png");

            assertThat(stored.bucket()).isEqualTo(BUCKET);
            assertThat(stored.version()).isNotBlank();
            assertThat(storage.open(stored.bucket(), stored.key(), stored.version()).getContentAsByteArray())
                    .containsExactly(bytes);
            assertThat(storage.delete(stored.bucket(), stored.key(), stored.version())).isTrue();
        }
    }
}
