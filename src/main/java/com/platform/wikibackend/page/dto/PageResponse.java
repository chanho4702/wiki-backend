package com.platform.wikibackend.page.dto;

import com.platform.wikibackend.domain.Page;

public record PageResponse(Long id, Long spaceId, Long parentId, String title, String content, Integer version) {
    public static PageResponse from(Page p) {
        return new PageResponse(p.getId(), p.getSpaceId(), p.getParentId(), p.getTitle(), p.getContent(), p.getVersion());
    }
}
