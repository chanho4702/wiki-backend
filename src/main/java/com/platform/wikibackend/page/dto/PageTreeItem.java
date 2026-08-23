package com.platform.wikibackend.page.dto;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.domain.PageType;

/** 본문은 싣지 않는다. type/status는 트리가 폴더 아이콘·초안 배지를 그리는 데 필요하다. */
public record PageTreeItem(Long id, Long parentId, String title, PageType type, PageStatus status,
                           Long position) {
    public static PageTreeItem from(Page p) {
        return new PageTreeItem(p.getId(), p.getParentId(), p.getTitle(), p.getType(), p.getStatus(),
                p.getSortOrder());
    }
}
