package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.PageVisit;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PageVisitRepository extends JpaRepository<PageVisit, PageVisit.Key> {

    Optional<PageVisit> findByUserIdAndPageId(Long userId, Long pageId);

    @Query("select v.pageId from PageVisit v where v.userId = :userId order by v.visitedAt desc, v.pageId desc")
    List<Long> findRecentPageIds(@Param("userId") long userId, Limit limit);

    /** 상한을 넘은 오래된 기록 — 지울 대상. 사용자 한 명의 목록이라 통째로 읽어도 짧다. */
    @Query("select v from PageVisit v where v.userId = :userId order by v.visitedAt desc, v.pageId desc")
    List<PageVisit> findAllByUser(@Param("userId") long userId);
}
