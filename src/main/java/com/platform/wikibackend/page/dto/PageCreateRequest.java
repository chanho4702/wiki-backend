package com.platform.wikibackend.page.dto;

import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.domain.PageType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** type/status는 선택 — 미지정 시 page/published(이 필드 도입 이전 클라이언트 호환). */
public record PageCreateRequest(
        @NotNull Long spaceId,
        Long parentId,
        @NotBlank @Size(max = 255) String title,
        @NotNull String content,
        PageType type,
        PageStatus status) {}
