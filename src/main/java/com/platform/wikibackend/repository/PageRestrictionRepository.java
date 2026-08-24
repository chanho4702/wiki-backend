package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.PageRestriction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PageRestrictionRepository extends JpaRepository<PageRestriction, Long> {
    List<PageRestriction> findByPageId(Long pageId);

    void deleteByPageId(Long pageId);

    /** 스페이스 스코프 일괄 로드 — 판정·트리 필터·이동 영향 계산이 이 한 쿼리를 공유한다(설계 §8). */
    @Query("""
            select r from PageRestriction r
             where r.pageId in (select p.id from Page p where p.spaceId = :spaceId)
            """)
    List<PageRestriction> findBySpaceId(@Param("spaceId") Long spaceId);
}
