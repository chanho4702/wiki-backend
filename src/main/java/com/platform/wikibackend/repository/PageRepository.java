package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.Page;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
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

    /* ── 지연 트리(2026-08-28) ────────────────────────────────────────────────────
     * 스페이스 전량을 내려주던 트리 엔드포인트는 스페이스가 커지면 그 자체가 병목이라 제거했다(2026-08-29).
     * 아래 쿼리들은 "지금 화면에 필요한 만큼"만 읽는다.
     */

    /** 직계 자식만. parentId가 null이면 루트 목록(파생 쿼리로는 null 비교가 안 돼 명시 비교). */
    @Query("""
            select new com.platform.wikibackend.page.dto.PageTreeItem(
                p.id, p.parentId, p.title, p.type, p.status, p.sortOrder, p.icon, p.updatedBy, p.updatedAt)
              from Page p
             where p.spaceId = :spaceId
               and ((:parentId is null and p.parentId is null) or p.parentId = :parentId)
             order by p.sortOrder asc, p.id asc
            """)
    List<com.platform.wikibackend.page.dto.PageTreeItem> findChildren(
            @Param("spaceId") Long spaceId, @Param("parentId") Long parentId);

    /** id 묶음으로 트리 항목 조회 — 조상 체인·후손 폐포가 id를 먼저 얻고 본문 없이 채운다. */
    @Query("""
            select new com.platform.wikibackend.page.dto.PageTreeItem(
                p.id, p.parentId, p.title, p.type, p.status, p.sortOrder, p.icon, p.updatedBy, p.updatedAt)
              from Page p
             where p.id in (:ids)
            """)
    List<com.platform.wikibackend.page.dto.PageTreeItem> findTreeItemsByIds(@Param("ids") Collection<Long> ids);

    /** 자식 수 — 트리가 펼침 화살표를 그릴지 정하려면 자식을 불러오기 전에 알아야 한다. */
    @Query("select p.parentId as parentId, count(p) as count from Page p"
            + " where p.parentId in (:parentIds) group by p.parentId")
    List<ParentCount> countChildren(@Param("parentIds") Collection<Long> parentIds);

    interface ParentCount {
        Long getParentId();
        long getCount();
    }

    /**
     * 최근 수정 순 — 스페이스 개요의 "최근 업데이트". 전량을 읽어 정렬하던 것을 대체한다.
     * 제한 필터로 몇 건 빠질 수 있어 화면이 필요한 수보다 넉넉히 받아 잘라 쓴다.
     */
    @Query("""
            select new com.platform.wikibackend.page.dto.PageTreeItem(
                p.id, p.parentId, p.title, p.type, p.status, p.sortOrder, p.icon, p.updatedBy, p.updatedAt)
              from Page p
             where p.spaceId = :spaceId
             order by p.updatedAt desc, p.id desc
            """)
    List<com.platform.wikibackend.page.dto.PageTreeItem> findRecentlyUpdated(
            @Param("spaceId") Long spaceId, org.springframework.data.domain.Limit limit);

    /** 제목 정확 일치(대소문자 무시) — `[[제목]]` 링크 해석용. 렌더러와 같은 기준이다. */
    @Query("""
            select new com.platform.wikibackend.page.dto.PageTreeItem(
                p.id, p.parentId, p.title, p.type, p.status, p.sortOrder, p.icon, p.updatedBy, p.updatedAt)
              from Page p
             where p.spaceId = :spaceId and lower(trim(p.title)) in (:titles)
             order by p.id asc
            """)
    List<com.platform.wikibackend.page.dto.PageTreeItem> findByTitles(
            @Param("spaceId") Long spaceId, @Param("titles") Collection<String> titles);

    /** 제목 부분 일치 — 사이드바 필터와 `[[` 자동완성이 쓴다(클라이언트 전량 필터 대체). */
    @Query("""
            select new com.platform.wikibackend.page.dto.PageTreeItem(
                p.id, p.parentId, p.title, p.type, p.status, p.sortOrder, p.icon, p.updatedBy, p.updatedAt)
              from Page p
             where p.spaceId = :spaceId and lower(p.title) like lower(concat('%', :q, '%'))
             order by p.title asc, p.id asc
            """)
    List<com.platform.wikibackend.page.dto.PageTreeItem> searchByTitle(
            @Param("spaceId") Long spaceId, @Param("q") String q, org.springframework.data.domain.Limit limit);

    /**
     * 후손 폐포 — 내보내기·서브트리 판정이 쓴다. 재귀 CTE 한 번이고 depth로 순환을 막는다.
     * (휴지통 포함이 아니다: 살아 있는 문서만 내보내고 판정한다.)
     */
    @Query(value = """
            with recursive sub(id, depth) as (
                select p.id, 0 from page p where p.id = :rootId and p.deleted_at is null
                union all
                select p.id, s.depth + 1
                  from page p join sub s on p.parent_id = s.id
                 where s.depth < 64 and p.deleted_at is null
            )
            select id from sub where id <> :rootId
            """, nativeQuery = true)
    List<Long> findDescendantIds(@Param("rootId") Long rootId);

    /** 서브트리 BFS용 (id, parentId) 경량 프로젝션 — 노드당 findByParentId N+1을 없앤다. */
    @Query("select p.id as id, p.parentId as parentId from Page p where p.spaceId = :spaceId")
    List<IdParent> findIdParentBySpaceId(@Param("spaceId") Long spaceId);

    interface IdParent {
        Long getId();
        Long getParentId();
    }

    /*
     * ── 휴지통(V13) 전용 네이티브 경로 ──────────────────────────────────────────────
     * Page에 걸린 @SQLRestriction("deleted_at is null")은 JPQL·파생 쿼리에만 붙는다.
     * 버려진 행을 읽어야 하는 곳(복원·영구삭제·목록·스페이스 정리)은 여기를 통해서만 접근한다.
     * 반환된 엔티티는 정상적으로 영속 상태라 dirty checking(restoreFromTrash)이 그대로 동작한다.
     */

    /**
     * 권한 인덱스용 (id, parentId) — **휴지통 포함**. 버려진 페이지를 빼면 조상 체인이 끊겨
     * 제한 상속이 사라진다(제한된 부모 밑에서 버려진 문서를 아무나 휴지통에서 보게 된다).
     */
    @Query(value = "select p.id as id, p.parent_id as parentId from page p where p.space_id = :spaceId",
            nativeQuery = true)
    List<IdParent> findIdParentAnyBySpaceId(@Param("spaceId") Long spaceId);

    /**
     * 조상 폐포(자기 자신 + 모든 조상)를 재귀 CTE 한 번으로. **휴지통 포함**(네이티브).
     *
     * 왜 있는가: 제한 판정은 대상 페이지의 조상 체인만 있으면 되는데, 예전에는 스페이스 전
     * 페이지를 읽어 부모 맵을 만들었다. 그러면 페이지 한 장 여는 비용이 스페이스 크기에
     * 비례한다(규모 검토 2026-08-28). 트리 필터처럼 진짜로 전량이 필요한 곳만 남긴다.
     *
     * depth 가드: 손상 데이터(parent_id 순환)에서 재귀가 끝나지 않는 것을 막는다 —
     * 인메모리 walk의 visited 셋과 같은 역할이다.
     */
    @Query(value = """
            with recursive chain(id, parent_id, depth) as (
                select p.id, p.parent_id, 0 from page p where p.id in (:ids)
                union all
                select p.id, p.parent_id, c.depth + 1
                  from page p join chain c on p.id = c.parent_id
                 where c.depth < 64
            )
            select distinct id, parent_id from chain
            """, nativeQuery = true)
    List<Object[]> findAncestorClosure(@Param("ids") Collection<Long> ids);

    /** 휴지통 포함 단건 조회. */
    @Query(value = "select * from page where id = :id", nativeQuery = true)
    Optional<Page> findAnyById(@Param("id") Long id);

    /** 휴지통 포함 스페이스 전량 — 스페이스 삭제 시 버려진 페이지의 첨부/리비전까지 치우려면 필요하다. */
    @Query(value = "select * from page where space_id = :spaceId", nativeQuery = true)
    List<Page> findAnyBySpaceId(@Param("spaceId") Long spaceId);

    /** 휴지통 포함 다건 조회 — 복원/영구삭제 묶음을 한 번에 로드한다. */
    @Query(value = "select * from page where id in (:ids)", nativeQuery = true)
    List<Page> findAnyByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * 휴지통 목록·복원 묶음 계산용 경량 행 — 본문(content)을 싣지 않는다.
     * 인터페이스 프로젝션이 아니라 Object[]인 이유: timestamptz를 드라이버가 OffsetDateTime으로
     * 주기도 Timestamp로 주기도 해서 Instant 프로젝션이 드라이버마다 다르게 깨진다(H2에서 실측).
     * 변환은 TrashRow.from이 한 곳에서 흡수한다.
     */
    @Query(value = """
            select p.id, p.parent_id, p.title, p.type, p.icon,
                   p.deleted_at, p.deleted_by, p.deleted_root
              from page p
             where p.space_id = :spaceId and p.deleted_at is not null
            """, nativeQuery = true)
    List<Object[]> findTrashedRows(@Param("spaceId") Long spaceId);

    /** 보존 기간이 지난 휴지통 루트 — 자손은 루트를 영구삭제할 때 함께 정리된다. */
    @Query(value = """
            select p.id from page p
             where p.deleted_at is not null and p.deleted_root = true and p.deleted_at < :before
             order by p.deleted_at
            """, nativeQuery = true)
    List<Long> findExpiredTrashRootIds(@Param("before") Instant before);


    /**
     * 영구 삭제 — 네이티브인 이유는 JPQL 대량 삭제(deleteAllInBatch)가 @SQLRestriction을 함께 받아
     * 버려진 행을 한 건도 지우지 못하기 때문이다(실측).
     */
    @Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "delete from page where id in (:ids)", nativeQuery = true)
    void deleteAllByIdIncludingTrashed(@Param("ids") Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select page from Page page where page.id = :id")
    Optional<Page> findByIdForUpdate(@Param("id") Long id);

    // 색인 백필용 keyset 페이징 — 전량을 한 번에 메모리에 올리지 않으려는 것.
    // OFFSET이 아니라 id 커서라, 스캔 중 앞쪽 행이 지워져도 건너뛰지 않는다.
    List<Page> findByIdGreaterThanOrderByIdAsc(Long afterId, Limit limit);
    List<Page> findBySpaceIdAndIdGreaterThanOrderByIdAsc(Long spaceId, Long afterId, Limit limit);

    /**
     * 라이트 검색 후보(OpenSearch 없는 배포) — 제목·본문 부분 일치.
     *
     * 형태소 분석이 없어 `like '%q%'`로 찾는다. 한국어는 교착어라 Postgres 기본 tsvector(simple)로는
     * "설정을"이 "설정"에 걸리지 않는데, 부분 문자열은 걸린다 — 소규모 설치에서는 이쪽이 실제로 쓸모 있다.
     * 인덱스는 V18의 pg_trgm GIN이 받는다(만들지 못한 DB에서는 순차 스캔으로 동작한다).
     *
     * 권한은 여기서 스페이스까지만 거른다 — 페이지 단위 제한(W18)은 후필터가 맡는다.
     */
    @Query("""
            select new com.platform.wikibackend.search.SearchRow(
                com.platform.wikibackend.search.DocType.PAGE,
                p.id, p.id, p.spaceId, s.key, s.name,
                cast(p.type as string), p.title, p.content, cast(null as string), p.updatedAt,
                case when lower(p.title) like :q then 3 else 1 end)
            from Page p join Space s on s.id = p.spaceId
            where p.spaceId in :spaceIds
              and (:includeDrafts = true or p.status = com.platform.wikibackend.domain.PageStatus.PUBLISHED)
              and (lower(p.title) like :q or lower(p.content) like :q)
              and (:anyAuthor = true or p.updatedBy in :authorIds)
              and (:after is null or p.updatedAt >= :after)
              and (:before is null or p.updatedAt <= :before)
              and (:anyLabel = true
                   or exists (select 1 from PageLabel l where l.pageId = p.id and l.name in :labels))
            order by case when lower(p.title) like :q then 3 else 1 end desc, p.updatedAt desc, p.id desc
            """)
    List<com.platform.wikibackend.search.SearchRow> searchPages(
            @Param("q") String q,
            @Param("spaceIds") Collection<Long> spaceIds,
            @Param("includeDrafts") boolean includeDrafts,
            @Param("anyAuthor") boolean anyAuthor,
            @Param("authorIds") Collection<Long> authorIds,
            @Param("after") java.time.Instant after,
            @Param("before") java.time.Instant before,
            @Param("anyLabel") boolean anyLabel,
            @Param("labels") Collection<String> labels,
            org.springframework.data.domain.Limit limit);

    /**
     * 라이트 검색 후보 — 첨부 파일명.
     *
     * 첨부에는 작성자·라벨이 없다. 그 필터가 걸린 질의는 첨부를 아예 찾지 않는다(호출부 판단) —
     * 여기서 조용히 무시하면 "작성자로 걸렀는데 남의 첨부가 나온다"가 된다.
     */
    @Query("""
            select new com.platform.wikibackend.search.SearchRow(
                com.platform.wikibackend.search.DocType.ATTACHMENT,
                a.id, a.pageId, p.spaceId, s.key, s.name,
                cast(null as string), cast(null as string), cast(null as string), a.filename, a.createdAt,
                3)
            from Attachment a
              join Page p on p.id = a.pageId
              join Space s on s.id = p.spaceId
            where p.spaceId in :spaceIds
              and a.lifecycleStatus = com.platform.wikibackend.attachment.AttachmentLifecycleStatus.CONFIRMED
              and lower(a.filename) like :q
              and (:after is null or a.createdAt >= :after)
              and (:before is null or a.createdAt <= :before)
            order by a.createdAt desc, a.id desc
            """)
    List<com.platform.wikibackend.search.SearchRow> searchAttachments(
            @Param("q") String q,
            @Param("spaceIds") Collection<Long> spaceIds,
            @Param("after") java.time.Instant after,
            @Param("before") java.time.Instant before,
            org.springframework.data.domain.Limit limit);
}
