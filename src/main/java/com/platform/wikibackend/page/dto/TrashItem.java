package com.platform.wikibackend.page.dto;

import com.platform.wikibackend.domain.PageType;

import java.time.Instant;

/**
 * 휴지통 한 줄(W21-1). 사용자가 직접 버린 페이지만 행이 되고, cascade로 딸려간 자손은
 * `descendantCount`로만 센다 — 하위 30개를 지운 사람에게 31줄을 보여주지 않는다.
 */
public record TrashItem(Long id, String title, PageType type, String icon,
                        Instant deletedAt, Long deletedBy, int descendantCount) {
}
