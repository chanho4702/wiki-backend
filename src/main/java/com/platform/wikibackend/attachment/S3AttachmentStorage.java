package com.platform.wikibackend.attachment;

import com.platform.common.error.ServiceUnavailableException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.InputStream;
import java.util.UUID;

public class S3AttachmentStorage implements AttachmentStorage {

    private final S3Client client;
    private final String bucket;

    public S3AttachmentStorage(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
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
