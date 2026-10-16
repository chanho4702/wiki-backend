package com.platform.wikibackend.space.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 스페이스 라벨 전량 교체 — 부분 추가/삭제 API를 두지 않고 화면이 최종 상태를 보낸다(문서 라벨과 같다). */
@Schema(description = "스페이스 라벨 전량 교체")
public record SpaceLabelsRequest(
        @Schema(description = "붙일 라벨 전부. 빈 배열이면 모두 뗀다", example = "[\"engineering\", \"runbook\"]")
        @NotNull List<String> labels) {
}
