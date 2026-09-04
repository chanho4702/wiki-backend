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
}
