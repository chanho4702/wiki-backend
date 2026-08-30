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
        Instant updatedAt,
        /** "inline"이면 본문 구간 댓글(V15). 아니면 "page". */
        String anchorType,
        String anchorQuote,
        Integer anchorOccurrence,
        Instant resolvedAt,
        /** 리액션 집계(W23). 목록에서 한 번에 채운다 — 댓글마다 따로 묻지 않는다. */
        java.util.List<com.platform.wikibackend.reaction.ReactionService.ReactionSummary> reactions
) {
    public static CommentResponse from(PageComment comment) {
        return from(comment, java.util.List.of());
    }

    public static CommentResponse from(PageComment comment,
            java.util.List<com.platform.wikibackend.reaction.ReactionService.ReactionSummary> reactions) {
        return new CommentResponse(comment.getId(), comment.getPageId(), comment.getParentId(),
                comment.getAuthorId(), comment.getAuthorName(), comment.getBody(),
                comment.getCreatedAt(), comment.getEditedAt(),
                comment.getAnchorType().toLowerCase(), comment.getAnchorQuote(),
                comment.getAnchorOccurrence(), comment.getResolvedAt(), reactions);
    }
}
