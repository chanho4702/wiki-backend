package com.platform.wikibackend.comment.dto;

import com.platform.wikibackend.domain.PageComment;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** updatedAt은 감사용 타임스탬프가 아니라 "본문이 수정된 시각"이다 — 수정 전에는 null. */
@Schema(description = "댓글 한 건. 답글은 parentId로 이어진다.")
public record CommentResponse(
        @Schema(description = "댓글 ID", example = "5") Long id,
        @Schema(description = "댓글이 달린 페이지 ID", example = "42") Long pageId,
        @Schema(description = "상위 댓글 ID. 최상위면 null", example = "3") Long parentId,
        @Schema(description = "작성자 사용자 ID", example = "7") Long authorId,
        @Schema(description = "작성 시점에 저장해 둔 작성자 표시명", example = "김찬호") String authorName,
        @Schema(description = "댓글 본문", example = "스테이징에서 먼저 돌려야 합니다") String body,
        @Schema(description = "작성 시각") Instant createdAt,
        @Schema(description = "본문을 고친 시각. 고친 적 없으면 null") Instant updatedAt,
        /** "inline"이면 본문 구간 댓글(V15). 아니면 "page". */
        @Schema(description = "inline이면 본문 구간 댓글, page면 일반 댓글", example = "page")
        String anchorType,
        @Schema(description = "인라인 댓글이 가리키는 본문 구간", example = "롤백은 태그로 되돌린다")
        String anchorQuote,
        @Schema(description = "그 구간이 본문에서 몇 번째로 등장하는지(0부터)", example = "0")
        Integer anchorOccurrence,
        @Schema(description = "해결 처리한 시각. 열려 있으면 null") Instant resolvedAt,
        /** 리액션 집계(W23). 목록에서 한 번에 채운다 — 댓글마다 따로 묻지 않는다. */
        @Schema(description = "이 댓글에 붙은 리액션 집계")
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
