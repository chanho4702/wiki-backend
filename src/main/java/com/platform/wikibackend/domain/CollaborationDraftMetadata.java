package com.platform.wikibackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * collaboration_document의 publish metadata projection.
 * state/version/updated_at은 collaboration-service 소유이므로 이 엔티티에서 읽거나 쓰지 않는다.
 */
@Entity
@Table(name = "collaboration_document")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollaborationDraftMetadata {

    @Id
    @Column(nullable = false, updatable = false)
    private String room;

    @Column(name = "base_page_version", nullable = false)
    private Long basePageVersion;

    @Column(nullable = false)
    private Long generation;

    /** 테스트 fixture와 향후 명시적 reset 경계에서만 사용한다. 최초 생성은 collaboration-service 책임이다. */
    public static CollaborationDraftMetadata of(long pageId, long basePageVersion, long generation) {
        CollaborationDraftMetadata metadata = new CollaborationDraftMetadata();
        metadata.room = room(pageId);
        metadata.basePageVersion = basePageVersion;
        metadata.generation = generation;
        return metadata;
    }

    public static String room(long pageId) {
        if (pageId <= 0) throw new IllegalArgumentException("페이지 ID는 양수여야 합니다");
        return "page:" + pageId;
    }

    /** page revision과 같은 transaction 안에서만 호출한다. */
    public void advanceTo(long nextPageVersion) {
        if (nextPageVersion != basePageVersion + 1) {
            throw new IllegalArgumentException("공동 초안 기준 버전은 한 단계씩 전진해야 합니다");
        }
        basePageVersion = nextPageVersion;
        generation += 1;
    }
}
