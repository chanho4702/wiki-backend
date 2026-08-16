package com.platform.wikibackend.domain;

import com.platform.wikibackend.attachment.StorageBackend;
import com.platform.wikibackend.attachment.StoredObject;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "attachment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "page_id", nullable = false, updatable = false)
    private Long pageId;

    @Column(nullable = false)
    private String filename;      // 원본 파일명 (표시용)

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "storage_key", nullable = false, unique = true, length = 64)
    private String storageKey;    // 디스크 파일명(UUID) — 원본명과 분리(경로 조작 차단)

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_backend", nullable = false, length = 16)
    private StorageBackend storageBackend;

    @Column(name = "storage_bucket", length = 255)
    private String storageBucket;

    @Column(name = "storage_version", length = 255)
    private String storageVersion;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private Long uploadedBy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public static Attachment of(Long pageId, String filename, String contentType,
                                Long sizeBytes, String storageKey, Long uploadedBy) {
        return of(pageId, filename, contentType, sizeBytes,
                new StoredObject(StorageBackend.LOCAL, null, storageKey, null), null, uploadedBy);
    }

    public static Attachment of(Long pageId, String filename, String contentType,
                                Long sizeBytes, StoredObject storedObject,
                                String checksumSha256, Long uploadedBy) {
        Attachment a = new Attachment();
        a.pageId = pageId;
        a.filename = filename;
        a.contentType = contentType;
        a.sizeBytes = sizeBytes;
        a.storageKey = storedObject.key();
        a.storageBackend = storedObject.backend();
        a.storageBucket = storedObject.bucket();
        a.storageVersion = storedObject.version();
        a.checksumSha256 = checksumSha256;
        a.uploadedBy = uploadedBy;
        return a;
    }
}
