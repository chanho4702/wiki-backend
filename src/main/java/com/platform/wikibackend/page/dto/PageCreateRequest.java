package com.platform.wikibackend.page.dto;

import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.domain.PageType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** type/status는 선택 — 미지정 시 page/published(이 필드 도입 이전 클라이언트 호환). */
@Schema(description = "페이지 생성 요청")
public record PageCreateRequest(
        @Schema(description = "페이지가 속할 스페이스 ID", example = "1")
        @NotNull Long spaceId,
        @Schema(description = "부모 페이지 ID. 비우면 스페이스 루트에 만든다", example = "12")
        Long parentId,
        @Schema(description = "페이지 제목", example = "배포 절차")
        @NotBlank @Size(max = 255) String title,
        @Schema(description = "마크다운 본문", example = "# 배포 절차\n\n1. 태그를 만든다")
        @NotNull String content,
        @Schema(description = "page(문서)·folder(폴더)·blog(블로그 글). 비우면 page", example = "page")
        PageType type,
        @Schema(description = "draft(초안) 또는 published(게시). 비우면 published", example = "published")
        PageStatus status) {}
