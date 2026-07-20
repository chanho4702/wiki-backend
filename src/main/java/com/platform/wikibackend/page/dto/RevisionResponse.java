package com.platform.wikibackend.page.dto;

import com.platform.wikibackend.domain.PageRevision;

public record RevisionResponse(Integer version, String title, String content, Long editedBy) {
    public static RevisionResponse from(PageRevision r) {
        return new RevisionResponse(r.getVersion(), r.getTitle(), r.getContent(), r.getEditedBy());
    }
}
