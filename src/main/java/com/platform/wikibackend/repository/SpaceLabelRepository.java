package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.SpaceLabel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SpaceLabelRepository extends JpaRepository<SpaceLabel, Long> {

    /**
     * 정렬은 이름(코드유닛) 오름차순 — 문서 라벨 칩과 같은 기준이라 목업·백엔드 모드의 칩 순서가
     * 갈리지 않는다.
     */
    List<SpaceLabel> findBySpaceIdOrderByName(Long spaceId);

    /** 디렉토리 목록 한 번에 — 스페이스마다 따로 묻는 N+1을 만들지 않는다. */
    List<SpaceLabel> findBySpaceIdInOrderByName(Collection<Long> spaceIds);

    /** 단일 bulk DELETE — 전량 교체와 스페이스 삭제가 쓴다(H2 테스트 스키마에는 FK cascade가 없다). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from SpaceLabel l where l.spaceId = :spaceId")
    int deleteBySpaceId(@Param("spaceId") Long spaceId);
}
