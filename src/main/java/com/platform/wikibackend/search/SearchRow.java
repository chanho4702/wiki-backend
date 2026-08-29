package com.platform.wikibackend.search;

import java.time.Instant;

/**
 * 검색 후보 한 줄 — 페이지와 첨부가 같은 모양으로 나온다.
 *
 * 두 질의를 합쳐 한 목록으로 정렬해야 해서 형태를 맞췄다. 페이지면 filename이 null,
 * 첨부면 content·pageType이 null이다.
 */
public record SearchRow(
        DocType docType,
        long id,
        Long ownerPageId,
        long spaceId,
        String spaceKey,
        String spaceName,
        String pageType,
        String title,
        String content,
        String filename,
        Instant updatedAt,
        /** 제목·파일명이 걸리면 3, 본문만 걸리면 1 — 목록 정렬 기준이다. */
        int score
) {}
