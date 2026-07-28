package com.platform.wikibackend.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 콘텐츠 타입 — 폴더는 "묶는 껍데기", 페이지는 "읽는 문서".
 * 별도 엔티티가 아니라 page의 컬럼으로 둔 결정은 프론트 기획 P1
 * (`wiki-front/docs/roadmap/2026-07-26-folder-and-editor-layout.md`).
 *
 * DB·enum 이름은 대문자, JSON 계약은 소문자("page"/"folder")다 — 프론트가 소문자를 쓴다.
 */
public enum PageType {
    PAGE, FOLDER;

    @JsonValue
    public String json() {
        return name().toLowerCase();
    }

    /** 미지정이면 page — 이 필드 도입 이전 클라이언트 호환. */
    @JsonCreator
    public static PageType from(String value) {
        return value == null ? PAGE : valueOf(value.toUpperCase());
    }
}
