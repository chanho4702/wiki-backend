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
     * 스레드 한 쪽 — 최상위 댓글을 최신부터(작성 시각, id) 키셋으로 자른다. 오프셋이 아니라서
     * 읽는 사이에 새 댓글이 달려도 다음 쪽에서 겹치거나 빠지지 않는다. 시각만으로 자르지 않는 이유:
     * 이관 댓글은 원본 시각을 되돌려 써서(W29) 같은 시각이 여럿일 수 있다.
     */
    @Query("""
            select c from PageComment c
             where c.pageId = :pageId and c.parentId is null and c.anchorType = :anchorType
               and (:first = true or c.createdAt < :at or (c.createdAt = :at and c.id < :id))
             order by c.createdAt desc, c.id desc
            """)
    List<PageComment> findThreadRoots(@Param("pageId") long pageId,
                                      @Param("anchorType") String anchorType,
                                      @Param("first") boolean first,
                                      @Param("at") java.time.Instant at,
                                      @Param("id") long id,
                                      org.springframework.data.domain.Limit limit);

    List<PageComment> findByParentIdInOrderByCreatedAtAscIdAsc(java.util.Collection<Long> parentIds);

    long countByPageIdAndParentIdIsNullAndAnchorType(long pageId, String anchorType);

    /** 그 종류(page/inline) 스레드에 달린 답글 수 — 답글 자신의 anchorType은 언제나 PAGE다. */
    @Query("""
            select count(c) from PageComment c
             where c.pageId = :pageId and c.parentId in (
                   select r.id from PageComment r
                    where r.pageId = :pageId and r.parentId is null and r.anchorType = :anchorType)
            """)
    long countRepliesOfKind(@Param("pageId") long pageId, @Param("anchorType") String anchorType);

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
