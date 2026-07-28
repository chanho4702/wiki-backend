package com.platform.wikibackend.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 게시 상태 — 만들자마자 트리에 세워두고 나중에 공개하는 초안 흐름(프론트 기획 P3).
 * 폴더는 게시 개념이 없어 항상 PUBLISHED로 만든다.
 *
 * DB·enum 이름은 대문자, JSON 계약은 소문자("draft"/"published")다.
 */
public enum PageStatus {
    DRAFT, PUBLISHED;

    @JsonValue
    public String json() {
        return name().toLowerCase();
    }

    /** 미지정이면 published — 초안 개념 도입 이전 문서는 전부 게시된 상태였다. */
    @JsonCreator
    public static PageStatus from(String value) {
        return value == null ? PUBLISHED : valueOf(value.toUpperCase());
    }
}
