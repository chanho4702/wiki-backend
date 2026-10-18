package com.platform.wikibackend.page.dto;

/**
 * 복사 옵션.
 *
 * 모든 값이 생략 가능하다 — 본문 없는 기존 호출(`POST /copy`)은 단일 페이지 복사 그대로다.
 *
 * 대상 위치(F-03, 2026-10-18): targetSpaceId·targetParentId를 둘 다 비우면 원본 옆(원본의 부모 아래)이다.
 * 하나라도 주면 그 자리다 — targetParentId가 비면 대상 스페이스의 루트, targetSpaceId가 비면 원본 스페이스.
 */
public record CopyRequest(Boolean includeDescendants, Boolean includeRestrictions,
                          Long targetSpaceId, Long targetParentId) {

    /** 구 호출부 호환(위치 미지정 = 원본 옆). */
    public CopyRequest(Boolean includeDescendants, Boolean includeRestrictions) {
        this(includeDescendants, includeRestrictions, null, null);
    }

    public boolean locationGiven() {
        return targetSpaceId != null || targetParentId != null;
    }

    public boolean descendantsIncluded() {
        return Boolean.TRUE.equals(includeDescendants);
    }

    /**
     * 제한은 **기본으로 함께 복사한다**(생략 시 true).
     *
     * 제한된 문서를 복사했는데 사본이 열려 있으면, 복사 한 번으로 스페이스 전체에 내용이 열린다.
     * 그 사고가 "사본에 제한이 남아 불편하다"보다 훨씬 나쁘다 — 열려면 명시적으로 끈다.
     */
    public boolean restrictionsIncluded() {
        return !Boolean.FALSE.equals(includeRestrictions);
    }
}
