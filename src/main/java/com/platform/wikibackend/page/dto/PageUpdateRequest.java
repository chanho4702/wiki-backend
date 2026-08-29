package com.platform.wikibackend.page.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PageUpdateRequest(
        @NotBlank @Size(max = 255) String title,
        @NotNull String content,
        Long parentId,                 // 현재 부모 ID. 부모 변경은 전용 move API를 사용한다.
        @NotNull Integer expectedVersion,
        /** 변경 요약(V17) — 선택. 비우면 이력에 요약 없이 남는다. */
        @Size(max = 500) String changeNote) {}
