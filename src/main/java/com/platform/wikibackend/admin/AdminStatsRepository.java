package com.platform.wikibackend.admin;

import com.platform.wikibackend.attachment.AttachmentLifecycleStatus;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * 관리자 대시보드 집계 전용 저장소(설계 §4.1).
 *
 * 아홉 개의 숫자가 다섯 엔티티에 흩어져 있어, 각 엔티티 저장소에 한 줄씩 심는 대신 여기에
 * 모았다. 이 화면이 무엇을 세는지 한 파일에서 읽히고, 세는 방식이 바뀌어도 도메인 저장소는
 * 건드리지 않는다.
 *
 * {@code Repository} 마커를 상속한다 — CRUD를 물려받지 않는다. 여기서 쓰기가 나갈 일이 없다.
 * 전부 COUNT/SUM 한 줄이고 새 인덱스를 요구하지 않는다.
 */
public interface AdminStatsRepository extends Repository<Page, Long> {

    @Query("select count(s) from Space s")
    long countSpaces();

    /**
     * 문서 수 — 폴더·블로그 글을 포함한다. 휴지통은 Page에 걸린
     * {@code @SQLRestriction("deleted_at is null")}이 알아서 뺀다.
     */
    @Query("select count(p) from Page p")
    long countPages();

    @Query("select count(p) from Page p where p.status = :status")
    long countPagesByStatus(@Param("status") PageStatus status);

    /**
     * 휴지통 문서 수. 네이티브라야 한다 — {@code @SQLRestriction}이 JPQL 카운트에도 붙어
     * 버려진 행을 한 건도 세지 못한다(PageRepository의 휴지통 블록과 같은 이유).
     */
    @Query(value = "select count(*) from page where deleted_at is not null", nativeQuery = true)
    long countTrashedPages();

    @Query("select count(r) from PageRevision r")
    long countRevisions();

    /** 최근 편집 수 — 리비전 한 건이 편집 한 번이다. */
    @Query("select count(r) from PageRevision r where r.createdAt >= :since")
    long countRevisionsSince(@Param("since") Instant since);

    /**
     * 첨부 수. CONFIRMED만 센다 — PENDING은 에디터가 저장 전에 닫혀 남은 임시 업로드라
     * 정리 잡이 곧 지운다. 그것까지 세면 화면의 숫자가 사용자가 보는 첨부 목록과 어긋난다.
     */
    @Query("select count(a) from Attachment a where a.lifecycleStatus = :status")
    long countAttachmentsByStatus(@Param("status") AttachmentLifecycleStatus status);

    /** coalesce가 필요하다 — 첨부가 하나도 없으면 SUM은 0이 아니라 null이다. */
    @Query("select coalesce(sum(a.sizeBytes), 0) from Attachment a where a.lifecycleStatus = :status")
    long sumAttachmentBytesByStatus(@Param("status") AttachmentLifecycleStatus status);

    /** 댓글 수 — 인라인 댓글과 답글을 모두 포함한다. */
    @Query("select count(c) from PageComment c")
    long countComments();
}
