package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.PageWatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PageWatchRepository extends JpaRepository<PageWatch, PageWatch.Key> {

    boolean existsByPageIdAndUserId(Long pageId, Long userId);

    void deleteByPageIdAndUserId(Long pageId, Long userId);

    void deleteByPageId(Long pageId);

    @Query("select w.userId from PageWatch w where w.pageId = :pageId")
    List<Long> findWatcherIds(@Param("pageId") Long pageId);

    /** 내 구독 목록(F-06) — 최근 구독한 것이 먼저. */
    @Query("select w.pageId from PageWatch w where w.userId = :userId order by w.createdAt desc, w.pageId desc")
    List<Long> findPageIdsByUser(@Param("userId") long userId);

    /** 일괄 해제(F-06) — 본인 행만, 한 문장. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @Query("delete from PageWatch w where w.userId = :userId and w.pageId in :ids")
    int deleteByUserIdAndPageIdIn(@Param("userId") long userId, @Param("ids") java.util.Collection<Long> ids);
}
