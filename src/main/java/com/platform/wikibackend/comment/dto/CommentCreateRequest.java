package com.platform.wikibackend.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * anchorQuote가 있으면 인라인 댓글(V15)이다 — 본문에서 선택한 텍스트와 그 텍스트가 몇 번째로
 * 등장하는지(0부터)를 함께 보낸다. 인라인 댓글은 최상위만 가능하다(답글은 parentId로 붙인다).
 */
public record CommentCreateRequest(
        @NotBlank(message = "코멘트 내용을 입력하세요")
        @Size(max = 10_000, message = "코멘트는 10,000자 이하여야 합니다")
        String body,
        @Positive(message = "parentId는 양수여야 합니다")
        Long parentId,
        @Size(max = 500, message = "인용 구간은 500자 이하여야 합니다")
        String anchorQuote,
        Integer anchorOccurrence
) {}
