package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.CollaborationDraftMetadata;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CollaborationDraftMetadataRepository
        extends JpaRepository<CollaborationDraftMetadata, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select draft from CollaborationDraftMetadata draft where draft.room = :room")
    Optional<CollaborationDraftMetadata> findByRoomForUpdate(@Param("room") String room);

    /**
     * 다른 경로 저장 뒤 공동 초안을 제자리 리셋한다 — 행을 지우면 collaboration-service가 메모리의 옛
     * Y.Doc을 다음 저장에서 되살리므로, 지우지 않고 base·generation·reset_epoch를 전진시키고 reset_pending을 켠다.
     * generation +1은 옛 세대 게시를 409로 막는 기존 효과, reset_epoch +1은 옛 세션 store를 막는 세대 가드용이다.
     * state는 NOT NULL을 채우는 빈 Y.Doc update다(로드는 reset_pending이라 거부된다).
     * 호출자가 page → draft 순서로 이미 잠근 뒤에만 부른다. 엔티티 캐시는 호출자가 refresh한다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            update collaboration_document
               set base_page_version = :basePageVersion,
                   generation = generation + 1,
                   reset_epoch = reset_epoch + 1,
                   reset_pending = true,
                   state = :emptyState,
                   version = version + 1,
                   updated_at = now()
             where room = :room
            """, nativeQuery = true)
    int resetForExternalWrite(@Param("room") String room,
                              @Param("basePageVersion") long basePageVersion,
                              @Param("emptyState") byte[] emptyState);
}
