package com.platform.wikibackend.search;

import java.time.Instant;

/**
 * 검색 후보의 정렬·권한 판정에 필요한 열만 — 본문(content)을 싣지 않는다.
 *
 * 후보는 많고 화면에 나가는 것은 한 페이지(최대 100건)뿐이다. 정렬·건수·권한은 이 가벼운 행으로
 * 끝내고, 본문(스니펫 재료)은 고른 한 페이지만 {@link SearchRow}로 다시 읽는다.
 */
public record SearchKey(
        DocType docType,
        long id,
        /** PAGE는 자신, ATTACHMENT는 소속 페이지 — 페이지 단위 제한(W18) 판정 대상. */
        Long ownerPageId,
        Instant updatedAt,
        /** 제목·파일명이 걸리면 3, 본문만 걸리면 1. */
        int score
) {}
