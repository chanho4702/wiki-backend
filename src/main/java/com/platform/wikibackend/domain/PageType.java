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
    PAGE, FOLDER,
    /**
     * 블로그 글(W24) — 트리 밖에 사는 문서. parent가 없고 날짜순으로 읽힌다(컨플루언스 블로그).
     * 별도 엔티티가 아니라 타입인 이유: 본문·리비전·댓글·라벨·검색·권한이 페이지와 전부 같다.
     * 다른 것은 "어디에 놓이는가"뿐이다.
     */
    BLOG;

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
