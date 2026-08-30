package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.SpaceStar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SpaceStarRepository extends JpaRepository<SpaceStar, SpaceStar.Key> {

    /** 별표한 순서(최근 별표가 먼저)로 스페이스 id만. 화면에 쓸 메타는 스페이스에서 따로 읽는다. */
    @Query("select s.spaceId from SpaceStar s where s.userId = :userId order by s.createdAt desc, s.spaceId desc")
    List<Long> findSpaceIds(@Param("userId") long userId);

    boolean existsByUserIdAndSpaceId(Long userId, Long spaceId);

    void deleteByUserIdAndSpaceId(Long userId, Long spaceId);
}
