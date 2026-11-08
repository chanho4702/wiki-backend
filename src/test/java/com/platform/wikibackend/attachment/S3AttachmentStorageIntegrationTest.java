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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class S3AttachmentStorageIntegrationTest {

    private static final String BUCKET = "wiki-integration";

    @Container
    static final GenericContainer<?> S3MOCK = new GenericContainer<>(DockerImageName.parse("adobe/s3mock:5.1.0"))
            .withExposedPorts(9090)
            .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", BUCKET);

    @Test
    void 한_노드가_저장한_버전_객체를_독립된_다른_노드가_조회_삭제한다() throws Exception {
        URI endpoint = URI.create("http://" + S3MOCK.getHost() + ":" + S3MOCK.getMappedPort(9090));
        try (S3Client writerClient = newClient(endpoint);
             S3Client readerClient = newClient(endpoint)) {
            writerClient.putBucketVersioning(PutBucketVersioningRequest.builder()
                    .bucket(BUCKET)
                    .versioningConfiguration(VersioningConfiguration.builder()
                            .status(BucketVersioningStatus.ENABLED)
                            .build())
                    .build());
            S3AttachmentStorage writerNode = new S3AttachmentStorage(writerClient, BUCKET);
            S3AttachmentStorage readerNode = new S3AttachmentStorage(readerClient, BUCKET);
            byte[] bytes = new byte[]{1, 2, 3, 4};

            StoredObject stored = writerNode.store(new ByteArrayInputStream(bytes), bytes.length, "image/png");

            assertThat(stored.bucket()).isEqualTo(BUCKET);
            assertThat(stored.version()).isNotBlank();
            assertThat(readerNode.open(stored.bucket(), stored.key(), stored.version()).getContentAsByteArray())
                    .containsExactly(bytes);
            assertThat(readerNode.delete(stored.bucket(), stored.key(), stored.version())).isTrue();
        }
    }

    /**
     * 고아 리포트용 전수 목록 — 페이지 경계를 넘어 키 오름차순으로 끝까지 흘리고, sink가 false면 멈춘다.
     * 페이지 크기를 2로 줄여 continuation token 경로를 실제로 탄다.
     */
    @Test
    void 버킷_전체를_페이지_넘어_키_순으로_흘리고_sink가_멈추면_멈춘다() throws Exception {
        URI endpoint = URI.create("http://" + S3MOCK.getHost() + ":" + S3MOCK.getMappedPort(9090));
        try (S3Client client = newClient(endpoint)) {
            client.createBucket(b -> b.bucket("wiki-inventory"));
            S3AttachmentStorage storage = new S3AttachmentStorage(client, "wiki-inventory", 2);
            List<String> stored = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                byte[] bytes = new byte[i + 1];
                stored.add(storage.store(new ByteArrayInputStream(bytes), bytes.length, "image/png").key());
            }
            stored.sort(null);

            List<ListedObject> all = new ArrayList<>();
            storage.forEachObject(o -> all.add(o));

            assertThat(storage.bucket()).isEqualTo("wiki-inventory");
            assertThat(all).extracting(ListedObject::key).containsExactlyElementsOf(stored);
            assertThat(all).allSatisfy(o -> {
                assertThat(o.size()).isPositive();
                assertThat(o.lastModified()).isNotNull();
            });

            List<String> firstThree = new ArrayList<>();
            storage.forEachObject(o -> {
                firstThree.add(o.key());
                return firstThree.size() < 3;
            });
            assertThat(firstThree).containsExactlyElementsOf(stored.subList(0, 3));
        }
    }

    private static S3Client newClient(URI endpoint) {
        return S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}
