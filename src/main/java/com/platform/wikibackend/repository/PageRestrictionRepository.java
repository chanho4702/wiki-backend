package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.PageRestriction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PageRestrictionRepository extends JpaRepository<PageRestriction, Long> {
    List<PageRestriction> findByPageId(Long pageId);

    /**
     * 단일 bulk DELETE(즉시 실행). 파생 deleteBy는 행을 읽어 지우기를 flush까지 미루고, Hibernate는
     * flush 때 INSERT를 DELETE보다 먼저 보낸다 — "지우고 다시 넣기" 교체가 같은 키를 다시 넣으면
     * 운영 PG의 유니크 제약에 걸렸다(H2 create-drop엔 제약이 없어 테스트가 못 봤다, 2026-10-18).
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from PageRestriction r where r.pageId = :pageId")
    int deleteByPageId(@Param("pageId") Long pageId);

    /** 조상 체인 스코프 조회 — 스페이스 전량을 읽지 않는 판정 경로가 쓴다(2026-08-28 규모 개선). */
    List<PageRestriction> findByPageIdIn(java.util.Collection<Long> pageIds);

    /**
     * 스페이스 스코프 일괄 로드 — 판정·트리 필터·이동 영향 계산이 이 한 쿼리를 공유한다(설계 §8).
     * 네이티브인 이유: JPQL 서브쿼리는 Page의 @SQLRestriction(휴지통)을 함께 받아 버려진 페이지의
     * 제한이 인덱스에서 빠진다 — 그러면 휴지통 목록·복원이 제한을 우회한다(V13).
     */
    @Query(value = """
            select r.* from page_restriction r
             where r.page_id in (select p.id from page p where p.space_id = :spaceId)
            """, nativeQuery = true)
    List<PageRestriction> findBySpaceId(@Param("spaceId") Long spaceId);

    /**
     * VIEW 제한이 하나라도 걸린 스페이스만(라이트 검색의 숨김 집합 계산). 제한 없는 스페이스는
     * 스페이스 전량 인덱스를 만들 필요가 없다. 휴지통 페이지의 제한도 센다 — 판정 인덱스와 같은 범위.
     */
    @Query(value = """
            select distinct p.space_id from page_restriction r
              join page p on p.id = r.page_id
             where r.type = 'VIEW' and p.space_id in (:spaceIds)
            """, nativeQuery = true)
    List<Long> findSpaceIdsWithViewRestrictions(@Param("spaceIds") java.util.Collection<Long> spaceIds);
}
