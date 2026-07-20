package com.platform.wikibackend.page.dto;

import com.platform.wikibackend.domain.PageRevision;

import java.time.Instant;

public record RevisionMeta(Integer version, Long editedBy, Instant createdAt) {
    public static RevisionMeta from(PageRevision r) {
        return new RevisionMeta(r.getVersion(), r.getEditedBy(), r.getCreatedAt());
    }
}
