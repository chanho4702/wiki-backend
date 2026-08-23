package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.PageComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PageCommentRepository extends JpaRepository<PageComment, Long> {
    List<PageComment> findByPageIdOrderByCreatedAtAscIdAsc(long pageId);

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
}
