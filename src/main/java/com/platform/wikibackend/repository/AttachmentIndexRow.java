package com.platform.wikibackend.repository;

import java.time.Instant;

/**
 * 색인 백필용 첨부 행. Attachment 엔티티에는 spaceId가 없어(페이지를 통해서만 안다)
 * 조인 결과를 담을 그릇이 따로 필요하다.
 */
public record AttachmentIndexRow(
        Long attachmentId,
        Long pageId,
        Long spaceId,
        String filename,
        String contentType,
        Long sizeBytes,
        Long uploadedBy,
        Instant createdAt
) {}
