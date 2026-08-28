package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.PageRestriction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PageRestrictionRepository extends JpaRepository<PageRestriction, Long> {
    List<PageRestriction> findByPageId(Long pageId);

    void deleteByPageId(Long pageId);

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
}
