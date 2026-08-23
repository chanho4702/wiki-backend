package com.platform.wikibackend.page.dto;

import jakarta.validation.constraints.Positive;

/**
 * 트리 이동/재정렬. `parentId` null = 루트, `beforeId` 앞(없거나 그룹에 없으면 맨 뒤)에 놓는다.
 * 내용 편집이 아니라 expectedVersion을 받지 않는다 — 드래그가 남의 저장을 409로 만들지 않는다.
 */
public record PageMoveRequest(
        @Positive(message = "parentId는 양수여야 합니다")
        Long parentId,
        @Positive(message = "beforeId는 양수여야 합니다")
        Long beforeId
) {}
