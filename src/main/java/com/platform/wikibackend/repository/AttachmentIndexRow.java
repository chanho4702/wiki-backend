package com.platform.wikibackend.repository;

import java.time.Instant;

/**
 * 색인용 첨부 행. Attachment 엔티티에는 spaceId도 스페이스 표시명도 없어(페이지를 통해서만 안다)
 * 조인 결과를 담을 그릇이 따로 필요하다.
 *
 * 표시명(key·name)까지 싣는 이유: 색인기가 첨부마다 스페이스를 되묻지 않게 하려는 것이다
 * (페이지 조달과 같은 비정규화 정책).
 */
public record AttachmentIndexRow(
        Long attachmentId,
        Long pageId,
        Long spaceId,
        String spaceKey,
        String spaceName,
        String filename,
        String contentType,
        Long sizeBytes,
        Long uploadedBy,
        Instant createdAt
) {}
