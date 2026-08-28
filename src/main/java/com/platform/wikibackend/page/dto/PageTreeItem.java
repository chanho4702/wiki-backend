package com.platform.wikibackend.page.dto;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.domain.PageType;

/**
 * 본문은 싣지 않는다. type/status는 트리가 폴더 아이콘·초안 배지를 그리는 데 필요하고,
 * updatedBy/updatedAt은 폴더 화면의 "마지막 편집" 열이 쓴다(2026-08-29) — 그것 때문에
 * 페이지를 한 건씩 다시 읽는 것이 더 비싸다.
 */
public record PageTreeItem(Long id, Long parentId, String title, PageType type, PageStatus status,
                           Long position, String icon, Long updatedBy, java.time.Instant updatedAt) {
    public static PageTreeItem from(Page p) {
        return new PageTreeItem(p.getId(), p.getParentId(), p.getTitle(), p.getType(), p.getStatus(),
                p.getSortOrder(), p.getIcon(), p.getUpdatedBy(), p.getUpdatedAt());
    }
}
