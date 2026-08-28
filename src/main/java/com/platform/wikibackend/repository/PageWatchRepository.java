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
}
