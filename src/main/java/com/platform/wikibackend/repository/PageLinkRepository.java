package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.PageLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PageLinkRepository extends JpaRepository<PageLink, Long> {

    void deleteBySourcePageId(Long sourcePageId);

    /**
     * 백링크 — 제목이 일치하는 링크를 가진 살아 있는 페이지들.
     * Page 조인이라 휴지통 문서는 자동으로 빠진다(버린 문서가 "여기서 링크 중"으로 보이면 안 된다).
     */
    @Query("""
            select p from PageLink l, Page p
             where l.sourcePageId = p.id
               and l.spaceId = :spaceId
               and l.targetTitle = :title
               and p.id <> :selfId
             order by p.title
            """)
    List<com.platform.wikibackend.domain.Page> findBacklinks(
            @Param("spaceId") Long spaceId, @Param("title") String title, @Param("selfId") Long selfId);
}
