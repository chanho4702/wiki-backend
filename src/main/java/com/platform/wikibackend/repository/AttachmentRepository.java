package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.Attachment;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    List<Attachment> findByPageId(Long pageId);
    void deleteByPageId(Long pageId);

    /** 색인 백필용 — 페이지 조인으로 spaceId를 붙인다. spaceId=0이면 전 스페이스. id 커서 페이징. */
    @Query("""
            select new com.platform.wikibackend.repository.AttachmentIndexRow(
                a.id, a.pageId, p.spaceId, a.filename, a.contentType, a.sizeBytes, a.uploadedBy, a.createdAt)
            from Attachment a join Page p on p.id = a.pageId
            where a.id > :afterId and (:spaceId = 0L or p.spaceId = :spaceId)
            order by a.id asc
            """)
    List<AttachmentIndexRow> findForIndexing(@Param("afterId") long afterId,
                                             @Param("spaceId") long spaceId,
                                             Limit limit);
}
