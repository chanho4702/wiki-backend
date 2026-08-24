package com.platform.wikibackend.page.dto;

import jakarta.validation.constraints.Positive;

/**
 * 트리 이동/재정렬. `parentId` null = 루트, `beforeId` 앞(없거나 그룹에 없으면 맨 뒤)에 놓는다.
 * 내용 편집이 아니라 expectedVersion을 받지 않는다 — 드래그가 남의 저장을 409로 만들지 않는다.
 *
 * `spaceId`를 주면 다른 스페이스로의 이동이다(생략 = 현재 스페이스). 이때 `children`이
 * 하위 처리 방식을 정한다:
 * - "with"(기본): 서브트리 전체가 함께 대상 스페이스로 간다(구조 유지).
 * - "promote": 하위는 현재 스페이스의 원래 부모 밑에 남고, 이 페이지만 옮긴다.
 * 같은 스페이스 이동에서 children은 무시된다 — 트리 이동은 언제나 하위를 데려간다.
 */
public record PageMoveRequest(
        @Positive(message = "spaceId는 양수여야 합니다")
        Long spaceId,
        @Positive(message = "parentId는 양수여야 합니다")
        Long parentId,
        @Positive(message = "beforeId는 양수여야 합니다")
        Long beforeId,
        String children,
        /** 이동 영향(새로 적용되는 보기 제한) 확인을 마쳤다는 표시 — 없으면 영향 발견 시 409. */
        Boolean confirmImpact
) {
    public boolean promoteChildren() {
        return "promote".equals(children);
    }

    public boolean impactConfirmed() {
        return Boolean.TRUE.equals(confirmImpact);
    }
}
