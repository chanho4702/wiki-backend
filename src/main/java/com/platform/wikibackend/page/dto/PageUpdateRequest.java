package com.platform.wikibackend.page.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "페이지 수정 요청. expectedVersion으로 낙관적 락을 건다.")
public record PageUpdateRequest(
        @Schema(description = "페이지 제목", example = "배포 절차")
        @NotBlank @Size(max = 255) String title,
        @Schema(description = "마크다운 본문 전체", example = "# 배포 절차\n\n1. 태그를 만든다")
        @NotNull String content,
        @Schema(description = "현재 부모 ID. 부모 변경은 이동 API를 쓴다", example = "12")
        Long parentId,                 // 현재 부모 ID. 부모 변경은 전용 move API를 사용한다.
        @Schema(description = "수정 직전에 읽은 페이지 버전. 현재 버전과 다르면 409", example = "3")
        @NotNull Integer expectedVersion,
        /** 변경 요약(V17) — 선택. 비우면 이력에 요약 없이 남는다. */
        @Schema(description = "변경 요약. 비우면 이력에 요약 없이 남는다", example = "롤백 절차 추가")
        @Size(max = 500) String changeNote) {}
