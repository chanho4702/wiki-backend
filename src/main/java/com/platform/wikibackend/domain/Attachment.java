package com.platform.wikibackend.domain;

import com.platform.wikibackend.attachment.StorageBackend;
import com.platform.wikibackend.attachment.StoredObject;
import com.platform.wikibackend.attachment.AttachmentLifecycleStatus;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false, length = 16)
    private AttachmentLifecycleStatus lifecycleStatus;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private Long uploadedBy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** 1부터. 같은 이름 재업로드마다 오른다 — 직전 내용은 attachment_version에 쌓인다(W23). */
    @Column(nullable = false)
    private Integer version;

    public static Attachment of(Long pageId, String filename, String contentType,
                                Long sizeBytes, String storageKey, Long uploadedBy) {
        return of(pageId, filename, contentType, sizeBytes,
                new StoredObject(StorageBackend.LOCAL, null, storageKey, null), null, uploadedBy,
                AttachmentLifecycleStatus.CONFIRMED);
    }

    public static Attachment of(Long pageId, String filename, String contentType,
                                Long sizeBytes, StoredObject storedObject,
                                String checksumSha256, Long uploadedBy) {
        return of(pageId, filename, contentType, sizeBytes, storedObject, checksumSha256, uploadedBy,
                AttachmentLifecycleStatus.CONFIRMED);
    }

    public static Attachment of(Long pageId, String filename, String contentType,
                                Long sizeBytes, StoredObject storedObject,
                                String checksumSha256, Long uploadedBy,
                                AttachmentLifecycleStatus lifecycleStatus) {
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
        a.lifecycleStatus = lifecycleStatus;
        a.confirmedAt = lifecycleStatus == AttachmentLifecycleStatus.CONFIRMED ? Instant.now() : null;
        a.version = 1;
        return a;
    }

    /**
     * 내용을 새 것으로 갈아끼운다 — **id도 파일명도 그대로다**.
     *
     * 그것이 이 기능의 요점이다: 본문의 인라인 참조는 id로 걸려 있으므로, 행을 갈아끼우면
     * 문서 어디에 박혀 있든 새 파일이 보인다. 새 행을 만들면 옛 파일이 계속 보인다.
     *
     * 부르기 전에 {@code AttachmentVersion.snapshotOf(this)}로 지금 내용을 떠 둬야 한다.
     */
    public void replaceWith(String contentType, Long sizeBytes, StoredObject storedObject,
                            String checksumSha256, Long uploaderId) {
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.storageKey = storedObject.key();
        this.storageBackend = storedObject.backend();
        this.storageBucket = storedObject.bucket();
        this.storageVersion = storedObject.version();
        this.checksumSha256 = checksumSha256;
        this.uploadedBy = uploaderId;
        this.version = this.version == null ? 2 : this.version + 1;
        // 되살아난 첨부는 다시 확정 상태다 — PENDING으로 남으면 정리 스케줄러가 지운다.
        this.lifecycleStatus = AttachmentLifecycleStatus.CONFIRMED;
        this.confirmedAt = Instant.now();
    }

    /** 멱등 확정 — 재시도나 reconciliation이 같은 첨부를 다시 확인해도 시각을 덮어쓰지 않는다. */
    public void confirm() {
        if (lifecycleStatus == AttachmentLifecycleStatus.PENDING) {
            lifecycleStatus = AttachmentLifecycleStatus.CONFIRMED;
            confirmedAt = Instant.now();
        }
    }
}
