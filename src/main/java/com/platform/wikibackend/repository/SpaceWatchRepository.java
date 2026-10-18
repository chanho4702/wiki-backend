package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.SpaceWatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SpaceWatchRepository extends JpaRepository<SpaceWatch, SpaceWatch.Key> {

    boolean existsBySpaceIdAndUserId(Long spaceId, Long userId);

    void deleteBySpaceIdAndUserId(Long spaceId, Long userId);

    void deleteBySpaceId(Long spaceId);

    @Query("select w.userId from SpaceWatch w where w.spaceId = :spaceId")
    List<Long> findWatcherIds(@Param("spaceId") Long spaceId);

    /** 내 구독 목록(F-06) — 최근 구독한 것이 먼저. */
    @Query("select w.spaceId from SpaceWatch w where w.userId = :userId order by w.createdAt desc, w.spaceId desc")
    List<Long> findSpaceIdsByUser(@Param("userId") long userId);

    /** 일괄 해제(F-06) — 본인 행만, 한 문장. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @Query("delete from SpaceWatch w where w.userId = :userId and w.spaceId in :ids")
    int deleteByUserIdAndSpaceIdIn(@Param("userId") long userId, @Param("ids") java.util.Collection<Long> ids);
}
