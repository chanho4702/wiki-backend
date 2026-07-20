package com.platform.wikibackend.page.dto;

import com.platform.wikibackend.domain.Page;

public record PageTreeItem(Long id, Long parentId, String title) {
    public static PageTreeItem from(Page p) {
        return new PageTreeItem(p.getId(), p.getParentId(), p.getTitle());
    }
}
