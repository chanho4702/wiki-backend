package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.Page;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PageRepository extends JpaRepository<Page, Long> {
    List<Page> findBySpaceIdOrderById(Long spaceId);

    /** 형제 그룹 — 루트는 parentId가 null이라 파생 쿼리로 못 쓰고 명시 비교한다(ALM findRankGroup과 동형). */
    @Query("""
            select p from Page p
             where p.spaceId = :spaceId
               and ((:parentId is null and p.parentId is null) or p.parentId = :parentId)
             order by p.sortOrder asc, p.id asc
            """)
    List<Page> findSiblings(@Param("spaceId") Long spaceId, @Param("parentId") Long parentId);

    /** 조회수 원자 증가(V10) — 엔티티 dirty-check 증가는 동시 조회에서 lost update가 나므로 금지.
     * clearAutomatically: 같은 트랜잭션에서 이미 로드한 엔티티의 stale viewCount가 남지 않게. */
    @Modifying(clearAutomatically = true)
    @Query("update Page p set p.viewCount = p.viewCount + 1 where p.id = :id")
    int incrementViewCount(@Param("id") Long id);

    @Query("select p.viewCount from Page p where p.id = :id")
    Long findViewCount(@Param("id") Long id);

    @Query("""
            select coalesce(max(p.sortOrder), 0) from Page p
             where p.spaceId = :spaceId
               and ((:parentId is null and p.parentId is null) or p.parentId = :parentId)
            """)
    long findMaxSortOrder(@Param("spaceId") Long spaceId, @Param("parentId") Long parentId);
    List<Page> findByParentId(Long parentId);

    /** 트리 응답 전용 프로젝션 — content(본문 text)를 로드하지 않는다. 스페이스가 커지면
     * 사이드바 트리 한 번에 전 문서 본문이 실려 오는 것이 최대 전송 낭비였다(규모 검토 2026-08-23). */
    @Query("""
            select new com.platform.wikibackend.page.dto.PageTreeItem(
                p.id, p.parentId, p.title, p.type, p.status, p.sortOrder, p.icon)
              from Page p
             where p.spaceId = :spaceId
             order by p.id
            """)
    List<com.platform.wikibackend.page.dto.PageTreeItem> findTreeBySpaceId(@Param("spaceId") Long spaceId);

    /** 서브트리 BFS용 (id, parentId) 경량 프로젝션 — 노드당 findByParentId N+1을 없앤다. */
    @Query("select p.id as id, p.parentId as parentId from Page p where p.spaceId = :spaceId")
    List<IdParent> findIdParentBySpaceId(@Param("spaceId") Long spaceId);

    interface IdParent {
        Long getId();
        Long getParentId();
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select page from Page page where page.id = :id")
    Optional<Page> findByIdForUpdate(@Param("id") Long id);

    // 색인 백필용 keyset 페이징 — 전량을 한 번에 메모리에 올리지 않으려는 것.
    // OFFSET이 아니라 id 커서라, 스캔 중 앞쪽 행이 지워져도 건너뛰지 않는다.
    List<Page> findByIdGreaterThanOrderByIdAsc(Long afterId, Limit limit);
    List<Page> findBySpaceIdAndIdGreaterThanOrderByIdAsc(Long spaceId, Long afterId, Limit limit);
}
