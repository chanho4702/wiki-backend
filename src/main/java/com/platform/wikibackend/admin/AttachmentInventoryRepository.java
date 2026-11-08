package com.platform.wikibackend.admin;

import com.platform.wikibackend.attachment.StorageBackend;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 고아 첨부 리포트가 읽는 행 키 — 엔티티를 올리지 않고 키 컬럼만 id 키셋 페이지로 읽는다.
 *
 * 메서드마다 짧은 읽기 트랜잭션이다. S3 목록을 넘기는 긴 시간 동안 DB 트랜잭션·커넥션을 잡지 않는다.
 */
@Repository
public class AttachmentInventoryRepository {

    /** 한 번에 읽는 행 수. 키셋이라 깊은 페이지에서도 OFFSET 비용이 없다. */
    static final int PAGE = 5_000;

    @PersistenceContext
    private EntityManager em;

    /** 첨부 행 또는 지난 버전 행 하나. {@code version}은 지난 버전 행일 때만 있다. */
    public record Row(long rowId, Long attachmentId, Long pageId, String key, StorageBackend backend,
                      String bucket, Integer version, Instant createdAt) {
    }

    /** 페이지가 사라졌는데 남은 행(page-missing) 또는 첨부가 사라졌는데 남은 지난 버전(attachment-missing). */
    public record PurgedRow(Long attachmentId, Long pageId, String key, Integer version) {
    }

    @Transactional(readOnly = true)
    public List<Row> currentRows(long afterId) {
        return em.createQuery("""
                        select a.id, a.pageId, a.storageKey, a.storageBackend, a.storageBucket, a.createdAt
                        from Attachment a where a.id > :after order by a.id""", Object[].class)
                .setParameter("after", afterId)
                .setMaxResults(PAGE)
                .getResultList().stream()
                .map(o -> new Row((Long) o[0], (Long) o[0], (Long) o[1], (String) o[2],
                        (StorageBackend) o[3], (String) o[4], null, (Instant) o[5]))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Row> versionRows(long afterId) {
        return em.createQuery("""
                        select v.id, v.attachmentId, a.pageId, v.storageKey, v.storageBackend, v.storageBucket,
                               v.version, v.createdAt
                        from AttachmentVersion v left join Attachment a on a.id = v.attachmentId
                        where v.id > :after order by v.id""", Object[].class)
                .setParameter("after", afterId)
                .setMaxResults(PAGE)
                .getResultList().stream()
                .map(o -> new Row((Long) o[0], (Long) o[1], (Long) o[2], (String) o[3],
                        (StorageBackend) o[4], (String) o[5], (Integer) o[6], (Instant) o[7]))
                .toList();
    }

    /**
     * page 행이 아예 없는 첨부. 네이티브인 이유: Page의 {@code @SQLRestriction("deleted_at is null")}을 피해
     * 휴지통 문서를 "있는 페이지"로 세야 한다(복원 가능한 첨부는 고아가 아니다).
     */
    @Transactional(readOnly = true)
    public long countPageMissing() {
        return ((Number) em.createNativeQuery("""
                select count(*) from attachment a
                where not exists (select 1 from page p where p.id = a.page_id)""")
                .getSingleResult()).longValue();
    }

    @Transactional(readOnly = true)
    public List<PurgedRow> samplePageMissing(int limit) {
        List<?> rows = em.createNativeQuery("""
                        select a.id, a.page_id, a.storage_key from attachment a
                        where not exists (select 1 from page p where p.id = a.page_id)
                        order by a.id""")
                .setMaxResults(limit)
                .getResultList();
        return rows.stream().map(r -> (Object[]) r)
                .map(o -> new PurgedRow(toLong(o[0]), toLong(o[1]), (String) o[2], null))
                .toList();
    }

    @Transactional(readOnly = true)
    public long countAttachmentMissing() {
        return ((Number) em.createNativeQuery("""
                select count(*) from attachment_version v
                where not exists (select 1 from attachment a where a.id = v.attachment_id)""")
                .getSingleResult()).longValue();
    }

    @Transactional(readOnly = true)
    public List<PurgedRow> sampleAttachmentMissing(int limit) {
        List<?> rows = em.createNativeQuery("""
                        select v.attachment_id, v.storage_key, v.version from attachment_version v
                        where not exists (select 1 from attachment a where a.id = v.attachment_id)
                        order by v.id""")
                .setMaxResults(limit)
                .getResultList();
        return rows.stream().map(r -> (Object[]) r)
                .map(o -> new PurgedRow(toLong(o[0]), null, (String) o[1], ((Number) o[2]).intValue()))
                .toList();
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
