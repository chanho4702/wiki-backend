package com.platform.wikibackend.comment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "댓글 본문 수정 요청")
public record CommentUpdateRequest(
        @Schema(description = "고쳐 쓸 댓글 본문", example = "스테이징 검증 후 진행하기로 했습니다")
        @NotBlank(message = "코멘트 내용을 입력하세요")
        @Size(max = 10_000, message = "코멘트는 10,000자 이하여야 합니다")
        String body
) {}
