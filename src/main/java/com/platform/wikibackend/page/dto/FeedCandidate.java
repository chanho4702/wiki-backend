package com.platform.wikibackend.page.dto;

import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.domain.PageType;

import java.time.Instant;

/**
 * 스페이스를 가로지르는 피드의 후보 행 — 트리 항목에 스페이스와 조회수만 더했다. 본문(content)은 싣지 않는다:
 * 피드 한 장을 위해 문서 수십 건의 본문을 읽을 이유가 없다.
 */
public record FeedCandidate(Long id, Long parentId, String title, PageType type, PageStatus status,
                            Long position, String icon, Long updatedBy, Instant updatedAt,
                            Long spaceId, Long viewCount) {

    public PageTreeItem item() {
        return new PageTreeItem(id, parentId, title, type, status, position, icon, updatedBy, updatedAt);
    }
}
