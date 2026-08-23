package com.platform.wikibackend.comment.dto;

import com.platform.wikibackend.domain.PageComment;

import java.time.Instant;

/** updatedAt은 감사용 타임스탬프가 아니라 "본문이 수정된 시각"이다 — 수정 전에는 null. */
public record CommentResponse(
        Long id,
        Long pageId,
        Long parentId,
        Long authorId,
        String authorName,
        String body,
        Instant createdAt,
        Instant updatedAt
) {
    public static CommentResponse from(PageComment comment) {
        return new CommentResponse(comment.getId(), comment.getPageId(), comment.getParentId(),
                comment.getAuthorId(), comment.getAuthorName(), comment.getBody(),
                comment.getCreatedAt(), comment.getEditedAt());
    }
}
