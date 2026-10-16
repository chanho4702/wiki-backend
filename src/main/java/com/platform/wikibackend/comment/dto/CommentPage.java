package com.platform.wikibackend.comment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 댓글 스레드 한 쪽.
 *
 * 쪽의 단위는 **스레드**(최상위 댓글 + 그 답글 전부)다. 답글만 다음 쪽으로 넘어가면 화면이 부모
 * 없는 답글을 받게 된다. 스레드는 최신부터 내려가고(다음 쪽 = 더 오래된 스레드), 답글은 스레드
 * 안에서 오래된 순이다 — 화면은 받은 스레드를 오래된 순으로 그리고 "이전 코멘트 더 보기"로 위를
 * 채운다. 새 댓글은 언제나 이미 받은 범위(최신 쪽) 끝에 붙으므로 빈틈이 생기지 않는다.
 */
@Schema(description = "댓글 스레드 한 쪽(스레드 최신순, 답글은 스레드 안에서 오래된 순)")
public record CommentPage(
        @Schema(description = "이 쪽의 최상위 댓글과 그 답글") List<CommentResponse> items,
        @Schema(description = "다음(더 오래된) 쪽을 읽을 때 before로 넘길 커서. 끝이면 null")
        String nextBefore,
        @Schema(description = "이 종류(page/inline)의 전체 스레드 수", example = "42") long threadCount,
        @Schema(description = "이 종류의 전체 댓글 수(답글 포함)", example = "120") long commentCount) {
}
