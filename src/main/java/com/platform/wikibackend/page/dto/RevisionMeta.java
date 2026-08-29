package com.platform.wikibackend.page.dto;

import com.platform.wikibackend.domain.PageRevision;

import java.time.Instant;

/** changeNote는 선택 입력이라 대개 null이다 — 화면은 있을 때만 그린다. */
public record RevisionMeta(Integer version, Long editedBy, Instant createdAt, String changeNote) {
    public static RevisionMeta from(PageRevision r) {
        return new RevisionMeta(r.getVersion(), r.getEditedBy(), r.getCreatedAt(), r.getChangeNote());
    }
}
