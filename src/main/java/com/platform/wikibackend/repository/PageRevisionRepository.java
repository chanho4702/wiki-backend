package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.PageRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PageRevisionRepository extends JpaRepository<PageRevision, Long> {
    List<PageRevision> findByPageIdOrderByVersionDesc(Long pageId);

    /** 보관 정리(W25) 후보 — 리비전이 keep보다 많은 페이지만. 대부분의 페이지는 여기 걸리지 않는다. */
    @Query("select r.pageId from PageRevision r group by r.pageId having count(r) > :keep")
    List<Long> findPageIdsWithMoreRevisionsThan(@Param("keep") long keep);
    Optional<PageRevision> findByPageIdAndVersion(Long pageId, Integer version);
    void deleteByPageId(Long pageId);

    /** 알림 "내가 수정한 페이지" 판정용 — 리비전을 남긴 편집자 목록. */
    @Query("select distinct r.editedBy from PageRevision r where r.pageId = :pageId")
    java.util.List<Long> findDistinctEditors(@Param("pageId") Long pageId);
}
