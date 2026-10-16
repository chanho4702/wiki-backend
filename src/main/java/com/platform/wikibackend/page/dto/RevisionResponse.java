package com.platform.wikibackend.page.dto;

import com.platform.wikibackend.domain.PageRevision;

import java.time.Instant;

/**
 * 버전 하나(본문 포함). createdAt·changeNote는 이력 목록(RevisionMeta)에만 있었는데, 목록을 쪽 단위로
 * 읽게 되면서 "v. 3 보기"처럼 목록 첫 쪽 밖의 버전을 주소로 열 때 메타를 얻을 곳이 이것뿐이라 함께 싣는다.
 */
public record RevisionResponse(Integer version, String title, String content, Long editedBy,
                               String editedByName, Instant createdAt, String changeNote) {
    public static RevisionResponse from(PageRevision r) {
        return from(r, r.getEditedByName());
    }

    /** 이름을 org 원장에서 채워 넣은 경우 — 저장된 스냅샷 대신 그 값을 싣는다(빈 자리에만). */
    public static RevisionResponse from(PageRevision r, String editedByName) {
        return new RevisionResponse(r.getVersion(), r.getTitle(), r.getContent(), r.getEditedBy(),
                editedByName, r.getCreatedAt(), r.getChangeNote());
    }
}
