package com.platform.wikibackend.comment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * anchorQuote가 있으면 인라인 댓글(V15)이다 — 본문에서 선택한 텍스트와 그 텍스트가 몇 번째로
 * 등장하는지(0부터)를 함께 보낸다. 인라인 댓글은 최상위만 가능하다(답글은 parentId로 붙인다).
 */
@Schema(description = "댓글 작성 요청. anchorQuote를 주면 본문 구간에 붙는 인라인 댓글이 된다.")
public record CommentCreateRequest(
        @Schema(description = "댓글 본문", example = "이 절차는 스테이징에서 먼저 돌려야 합니다")
        @NotBlank(message = "코멘트 내용을 입력하세요")
        @Size(max = 10_000, message = "코멘트는 10,000자 이하여야 합니다")
        String body,
        @Schema(description = "답글을 달 상위 댓글 ID. 비우면 최상위 댓글", example = "5")
        @Positive(message = "parentId는 양수여야 합니다")
        Long parentId,
        @Schema(description = "본문에서 선택한 인용 구간. 주면 인라인 댓글이 된다", example = "롤백은 태그로 되돌린다")
        @Size(max = 500, message = "인용 구간은 500자 이하여야 합니다")
        String anchorQuote,
        @Schema(description = "그 인용 구간이 본문에서 몇 번째로 등장하는지(0부터)", example = "0")
        Integer anchorOccurrence
) {}
