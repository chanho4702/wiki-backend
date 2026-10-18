package com.platform.wikibackend.page.dto;

import com.platform.wikibackend.domain.PageStatus;

import java.time.Instant;

/**
 * 블로그 목록 행 프로젝션 — 본문 대신 앞부분(head)만 읽는다. 목록이 글 수만큼 본문 전체를 읽고
 * 정규식 9개를 돌리던 비용을 없앤다(감사 W-16). 발췌는 head에서 만든다.
 */
public record BlogRow(Long id, String title, PageStatus status, String icon,
                      Long createdBy, Long updatedBy, Instant createdAt, Instant updatedAt, String head) {

    /** 발췌(200자)를 만들기에 충분한 앞부분 — 기호·코드 블록을 걷어내도 남도록 넉넉히. */
    public static final int HEAD_LENGTH = 2000;
}
