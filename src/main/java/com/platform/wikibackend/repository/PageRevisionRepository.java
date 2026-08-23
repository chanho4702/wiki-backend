package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.PageRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PageRevisionRepository extends JpaRepository<PageRevision, Long> {
    List<PageRevision> findByPageIdOrderByVersionDesc(Long pageId);
    Optional<PageRevision> findByPageIdAndVersion(Long pageId, Integer version);
    void deleteByPageId(Long pageId);

    /** 알림 "내가 수정한 페이지" 판정용 — 리비전을 남긴 편집자 목록. */
    @Query("select distinct r.editedBy from PageRevision r where r.pageId = :pageId")
    java.util.List<Long> findDistinctEditors(@Param("pageId") Long pageId);
}
