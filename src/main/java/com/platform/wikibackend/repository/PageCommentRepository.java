package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.PageComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PageCommentRepository extends JpaRepository<PageComment, Long> {
    List<PageComment> findByPageIdOrderByCreatedAtAscIdAsc(long pageId);

    /** 이관 검증 조회(W29 X1) — 본문을 끌어오지 않고 건수만 센다. */
    long countByPageId(long pageId);

    List<PageComment> findByParentIdOrderByCreatedAtAscIdAsc(long parentId);

    /**
     * 단일 bulk DELETE — 파생 deleteBy는 행을 하나씩 지워서, 운영 PG에서 최상위 댓글 삭제가
     * 답글을 cascade로 먼저 지운 뒤 이어지는 답글 개별 DELETE가 0행 → StaleStateException을 낸다.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from PageComment c where c.pageId = :pageId")
    int deleteByPageId(@Param("pageId") long pageId);

    /** 최상위 댓글 삭제용 — H2 테스트 스키마에는 FK cascade가 없어 답글을 함께 지워야 한다. */
    @Modifying(flushAutomatically = true)
    @Query("delete from PageComment c where c.id = :id or c.parentId = :id")
    int deleteWithReplies(@Param("id") long id);

    /**
     * 이관 댓글의 원본 작성 시각 보존(W29 M3). @CreationTimestamp가 INSERT에서 "지금"으로
     * 덮어쓰기 때문에 저장 뒤 한 번 더 눌러야 한다 — 2019년 댓글이 이관한 날짜로 달리면
     * 대화의 순서와 맥락이 통째로 거짓이 된다. page·page_revision과 같은 처리다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update page_comment set created_at = :createdAt, updated_at = :createdAt"
            + " where id = :id", nativeQuery = true)
    int overwriteCreatedAt(@Param("id") Long id, @Param("createdAt") java.time.Instant createdAt);
}
