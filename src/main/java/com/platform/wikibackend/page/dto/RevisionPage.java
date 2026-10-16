package com.platform.wikibackend.page.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 버전 이력 한 쪽 — 최신 버전부터 내려간다.
 *
 * 커서는 버전 번호다: 리비전은 페이지 안에서 번호가 유일하고 내림차순이 곧 시간 역순이라, 다음
 * 쪽은 "이 번호보다 작은 것"이면 된다. 오프셋이 아니므로 읽는 사이에 새 버전이 저장돼도 다음 쪽에서
 * 항목이 겹치거나 빠지지 않는다.
 */
@Schema(description = "버전 이력 한 쪽(최신순)")
public record RevisionPage(
        @Schema(description = "이 쪽의 버전 메타(본문 없음), 버전 내림차순") List<RevisionMeta> items,
        @Schema(description = "다음 쪽을 읽을 때 before로 넘길 버전 번호. 끝이면 null", example = "51")
        Integer nextBefore,
        @Schema(description = "이 페이지의 전체 버전 수", example = "240") long total) {
}
