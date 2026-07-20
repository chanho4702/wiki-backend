package com.platform.wikibackend.domain;

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

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private Long uploadedBy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public static Attachment of(Long pageId, String filename, String contentType,
                                Long sizeBytes, String storageKey, Long uploadedBy) {
        Attachment a = new Attachment();
        a.pageId = pageId;
        a.filename = filename;
        a.contentType = contentType;
        a.sizeBytes = sizeBytes;
        a.storageKey = storageKey;
        a.uploadedBy = uploadedBy;
        return a;
    }
}
