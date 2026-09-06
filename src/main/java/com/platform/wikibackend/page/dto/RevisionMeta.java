package com.platform.wikibackend.page.dto;

import com.platform.wikibackend.domain.PageRevision;

import java.time.Instant;

/** changeNote는 선택 입력이라 대개 null이다 — 화면은 있을 때만 그린다. */
public record RevisionMeta(Integer version, Long editedBy, Instant createdAt, String changeNote,
                           /** 저장 시점 편집자 이름(V28). 구버전 리비전은 null. */
                           String editedByName) {
    public static RevisionMeta from(PageRevision r) {
        return from(r, r.getEditedByName());
    }

    /** 이름을 org 원장에서 채워 넣은 경우 — 저장된 스냅샷 대신 그 값을 싣는다(빈 자리에만). */
    public static RevisionMeta from(PageRevision r, String editedByName) {
        return new RevisionMeta(r.getVersion(), r.getEditedBy(), r.getCreatedAt(), r.getChangeNote(),
                editedByName);
    }
}
