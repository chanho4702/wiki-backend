package com.platform.wikibackend.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CommentCreateRequest(
        @NotBlank(message = "코멘트 내용을 입력하세요")
        @Size(max = 10_000, message = "코멘트는 10,000자 이하여야 합니다")
        String body,
        @Positive(message = "parentId는 양수여야 합니다")
        Long parentId
) {}
