package com.platform.wikibackend.attachment;

import com.platform.common.error.ServiceUnavailableException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.InputStream;
import java.util.UUID;
import java.util.function.Predicate;

public class S3AttachmentStorage implements AttachmentStorage, AttachmentObjectInventory {

    private final S3Client client;
    private final String bucket;
    private final int listPageSize;

    public S3AttachmentStorage(S3Client client, String bucket) {
        this(client, bucket, 1000);
    }

    /** listPageSize는 테스트가 continuation token 경로를 타려고 줄인다(운영은 S3 최대치 1000). */
    S3AttachmentStorage(S3Client client, String bucket, int listPageSize) {
        this.client = client;
        this.bucket = bucket;
        this.listPageSize = listPageSize;
    }

    @Override
    public String bucket() {
        return bucket;
    }

    /**
     * ListObjectsV2를 continuation token으로 끝까지 넘긴다 — 한 번에 한 페이지만 메모리에 둔다.
     * 버전 관리 버킷이어도 현재 버전만 보인다(이전 버전·삭제 마커는 ListObjectVersions 몫).
     */
    @Override
    public void forEachObject(Predicate<ListedObject> sink) {
        String token = null;
        try {
            do {
                ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                        .bucket(bucket).maxKeys(listPageSize);
                if (token != null) request.continuationToken(token);
                ListObjectsV2Response page = client.listObjectsV2(request.build());
                for (S3Object o : page.contents()) {
                    long size = o.size() == null ? 0L : o.size();
                    if (!sink.test(new ListedObject(o.key(), size, o.lastModified()))) return;
                }
                token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
            } while (token != null);
        } catch (SdkException e) {
            throw new ServiceUnavailableException("S3 첨부 목록 조회 실패", e);
        }
    }

    @Override
    public StorageBackend backend() {
        return StorageBackend.S3;
    }

    @Override
    public StoredObject store(InputStream input, long contentLength, String contentType) {
        String key = UUID.randomUUID().toString();
        try {
            PutObjectResponse response = client.putObject(PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .build(), RequestBody.fromInputStream(input, contentLength));
            return new StoredObject(backend(), bucket, key, response.versionId());
        } catch (RuntimeException e) {
            throw new ServiceUnavailableException("S3 첨부 저장 실패", e);
        }
    }

    @Override
    public Resource open(String bucket, String key, String version) {
        try {
            GetObjectRequest.Builder request = GetObjectRequest.builder().bucket(requireBucket(bucket)).key(key);
            if (version != null && !version.isBlank()) request.versionId(version);
            ResponseInputStream<GetObjectResponse> input = client.getObject(request.build());
            long contentLength = input.response().contentLength();
            return new InputStreamResource(input) {
                @Override
                public long contentLength() {
                    return contentLength;
                }
            };
        } catch (RuntimeException e) {
            throw new ServiceUnavailableException("S3 첨부 조회 실패", e);
        }
    }

    @Override
    public boolean delete(String bucket, String key, String version) {
        try {
            DeleteObjectRequest.Builder request = DeleteObjectRequest.builder().bucket(requireBucket(bucket)).key(key);
            if (version != null && !version.isBlank()) request.versionId(version);
            client.deleteObject(request.build());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String requireBucket(String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("S3 첨부 bucket 메타데이터가 없습니다");
        }
        return bucket;
    }
}
