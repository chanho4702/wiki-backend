package com.platform.wikibackend.page.dto;

import com.platform.wikibackend.domain.PageRevision;

public record RevisionResponse(Integer version, String title, String content, Long editedBy,
                               String editedByName) {
    public static RevisionResponse from(PageRevision r) {
        return from(r, r.getEditedByName());
    }

    /** 이름을 org 원장에서 채워 넣은 경우 — 저장된 스냅샷 대신 그 값을 싣는다(빈 자리에만). */
    public static RevisionResponse from(PageRevision r, String editedByName) {
        return new RevisionResponse(r.getVersion(), r.getTitle(), r.getContent(), r.getEditedBy(),
                editedByName);
    }
}
