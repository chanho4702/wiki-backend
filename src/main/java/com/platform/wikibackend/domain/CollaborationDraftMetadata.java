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
 * 예외는 다른 경로 저장의 리셋 경계 하나다 — 그때만 네이티브 UPDATE가 state를 빈 Y.Doc으로 덮는다
 * ({@code CollaborationDraftMetadataRepository#resetForExternalWrite}).
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

    /** 다른 경로 저장으로 초안이 무효가 되어 새 기준(bootstrap)을 기다리는 중(V40). */
    @Column(name = "reset_pending", nullable = false)
    private boolean resetPending;

    /**
     * 리셋할 때만 +1 되는 단조 카운터(V40). collaboration-service의 store 가드와 티켓 draftEpoch가 본다.
     * generation과 따로 두는 이유: generation은 정상 게시에서도 +1 되어 가드로 쓰면 게시 한 번에
     * 남은 편집자를 쫓아낸다(리뷰 C-1). 게시({@link #advanceTo})는 이 값을 바꾸지 않는다.
     */
    @Column(name = "reset_epoch", nullable = false)
    private long resetEpoch;

    /** 테스트 fixture와 향후 명시적 reset 경계에서만 사용한다. 최초 생성은 collaboration-service 책임이다. */
    public static CollaborationDraftMetadata of(long pageId, long basePageVersion, long generation) {
        CollaborationDraftMetadata metadata = new CollaborationDraftMetadata();
        metadata.room = room(pageId);
        metadata.basePageVersion = basePageVersion;
        metadata.generation = generation;
        metadata.resetPending = false;
        metadata.resetEpoch = 0;
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
