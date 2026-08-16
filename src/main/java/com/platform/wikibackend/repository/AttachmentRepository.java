package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.Attachment;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.time.Instant;
import jakarta.persistence.LockModeType;

import com.platform.wikibackend.attachment.AttachmentLifecycleStatus;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    List<Attachment> findByPageId(Long pageId);
    void deleteByPageId(Long pageId);

    @Query("""
            select a from Attachment a
            where a.lifecycleStatus = :status and a.createdAt < :cutoff
            order by a.id asc
            """)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Attachment> findExpiredByLifecycleStatus(
            @Param("status") AttachmentLifecycleStatus status,
            @Param("cutoff") Instant cutoff,
            Pageable pageable);

    /**
     * 색인 백필용 — 페이지·스페이스를 조인해 spaceId와 표시명을 붙인다. spaceId=0이면 전 스페이스.
     * id 커서 페이징.
     */
    @Query("""
            select new com.platform.wikibackend.repository.AttachmentIndexRow(
                a.id, a.pageId, p.spaceId, s.key, s.name,
                a.filename, a.contentType, a.sizeBytes, a.uploadedBy, a.createdAt)
            from Attachment a
              join Page p on p.id = a.pageId
              join Space s on s.id = p.spaceId
            where a.id > :afterId
              and a.lifecycleStatus = com.platform.wikibackend.attachment.AttachmentLifecycleStatus.CONFIRMED
              and (:spaceId = 0L or p.spaceId = :spaceId)
            order by a.id asc
            """)
    List<AttachmentIndexRow> findForIndexing(@Param("afterId") long afterId,
                                             @Param("spaceId") long spaceId,
                                             Limit limit);

    /** 단건 조달 — 이벤트 페이로드에 없는 필드를 채우려면 필요하다(전량 스캔은 비용이 맞지 않는다). */
    @Query("""
            select new com.platform.wikibackend.repository.AttachmentIndexRow(
                a.id, a.pageId, p.spaceId, s.key, s.name,
                a.filename, a.contentType, a.sizeBytes, a.uploadedBy, a.createdAt)
            from Attachment a
              join Page p on p.id = a.pageId
              join Space s on s.id = p.spaceId
            where a.id = :attachmentId
              and a.lifecycleStatus = com.platform.wikibackend.attachment.AttachmentLifecycleStatus.CONFIRMED
            """)
    java.util.Optional<AttachmentIndexRow> findForIndexingById(@Param("attachmentId") long attachmentId);
}
