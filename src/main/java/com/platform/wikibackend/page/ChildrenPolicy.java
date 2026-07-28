package com.platform.wikibackend.page;

/**
 * 자식이 있는 페이지·폴더를 지울 때 자식을 어떻게 할지 (프론트 기획 P2).
 *
 * 미지정(null)이면 자식이 있을 때 삭제를 거부한다 — 호출 실수 한 번으로 문서 트리가 통째로
 * 사라지지 않게 하는 기본값이고, 프론트 목업 계약과도 같다.
 */
public enum ChildrenPolicy {
    /** 자식을 삭제 대상의 부모로 올리고 대상만 지운다. */
    PROMOTE,
    /** 후손 전부를 함께 지운다. */
    CASCADE;

    /**
     * 쿼리 파라미터(소문자) → 상수. Spring 기본 enum 변환은 대소문자를 구분해
     * `?children=promote`를 거부하므로 컨트롤러가 이 팩토리를 쓴다.
     */
    public static ChildrenPolicy from(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("children은 promote 또는 cascade여야 합니다: " + value);
        }
    }
}
