package com.platform.wikibackend.domain;

import com.platform.wikibackend.attachment.StorageBackend;
import com.platform.wikibackend.attachment.StoredObject;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 첨부의 지난 버전(W23) — 현재 내용은 {@link Attachment} 행에 있고, 밀려난 내용이 여기 쌓인다.
 *
 * 저장 객체는 지우지 않는다. 지우면 "지난 버전 받기"가 껍데기만 남는다 — 첨부 자체를 지울 때만
 * 함께 치운다.
 */
@Entity
@Table(name = "attachment_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttachmentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attachment_id", nullable = false, updatable = false)
    private Long attachmentId;

    @Column(nullable = false, updatable = false)
    private Integer version;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_backend", nullable = false, length = 16)
    private StorageBackend storageBackend;

    @Column(name = "storage_bucket", length = 255)
    private String storageBucket;

    @Column(name = "storage_key", nullable = false, length = 64)
    private String storageKey;

    @Column(name = "storage_version", length = 255)
    private String storageVersion;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private Long uploadedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 지금 현재인 내용을 그대로 떠서 보관한다 — 밀려나기 직전에 부른다. */
    public static AttachmentVersion snapshotOf(Attachment current) {
        AttachmentVersion v = new AttachmentVersion();
        v.attachmentId = current.getId();
        v.version = current.getVersion();
        v.contentType = current.getContentType();
        v.sizeBytes = current.getSizeBytes();
        v.storageBackend = current.getStorageBackend();
        v.storageBucket = current.getStorageBucket();
        v.storageKey = current.getStorageKey();
        v.storageVersion = current.getStorageVersion();
        v.checksumSha256 = current.getChecksumSha256();
        v.uploadedBy = current.getUploadedBy();
        return v;
    }

    public StoredObject storedObject() {
        return new StoredObject(storageBackend, storageBucket, storageKey, storageVersion);
    }
}
