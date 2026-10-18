package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.Page;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 라이트 검색(OpenSearch 없는 배포) 전용 질의 — PageRepository에서 분리(2026-10-18).
 * 검색 질의는 정렬·권한 제외 규칙이 LiteSearchService와 한 묶음으로 바뀐다. 트리·휴지통 질의와 섞여
 * 있으면 600줄 리포지토리에서 그 짝을 찾기 어렵다.
 */
public interface PageSearchRepository extends Repository<Page, Long> {

    /*
     * ── 라이트 검색(OpenSearch 없는 배포) ────────────────────────────────────────────
     * 형태소 분석이 없어 `like '%q%'`로 찾는다. 한국어는 교착어라 Postgres 기본 tsvector(simple)로는
     * "설정을"이 "설정"에 걸리지 않는데, 부분 문자열은 걸린다 — 소규모 설치에서는 이쪽이 실제로 쓸모 있다.
     * 인덱스는 V18의 pg_trgm GIN이 받는다(만들지 못한 DB에서는 순차 스캔으로 동작한다).
     *
     * 권한: 스페이스는 spaceIds로, 페이지 단위 제한(W18)은 **숨길 페이지 집합(hiddenIds)을 SQL에서
     * 제외**한다. 예전에는 후보를 500건으로 자른 뒤 메모리에서 걸렀고, 앞쪽 500건이 전부 제한
     * 문서면 그 뒤의 볼 수 있는 문서가 0건으로 사라졌다(2026-10-16 결함). 이제 건수(count)와
     * 페이지 경계가 권한 적용 뒤의 값이다.
     *
     * 세 갈래(키·건수·본문)로 나눈 이유: 정렬·건수·페이지 자르기에는 본문이 필요 없다. 본문은 화면에
     * 나갈 한 페이지만 id로 다시 읽어 스니펫을 만든다 — 후보 수만큼 본문을 메모리에 올리지 않는다.
     *
     * 정렬은 LiteSearchService.comparator와 **같아야 한다**: SQL이 자른 앞부분과 메모리 병합 순서가
     * 다르면 페이지를 넘길 때 항목이 겹치거나 빠진다(2026-09-13 결함과 같은 계열).
     */
    String LITE_PAGE_WHERE = """
             from Page p
            where p.spaceId in :spaceIds and p.archivedAt is null
              and (:includeDrafts = true or p.status = com.platform.wikibackend.domain.PageStatus.PUBLISHED)
              and (lower(p.title) like :q escape '\\' or lower(p.content) like :q escape '\\')
              and (:anyAuthor = true or p.updatedBy in :authorIds)
              and (cast(:after as java.time.Instant) is null or p.updatedAt >= :after)
              and (cast(:before as java.time.Instant) is null or p.updatedAt <= :before)
              and (:anyLabel = true
                   or exists (select 1 from PageLabel l where l.pageId = p.id and l.name in :labels))
              and (:noHidden = true or p.id not in :hiddenIds)
            """;

    String LITE_PAGE_KEYS = """
            select new com.platform.wikibackend.search.SearchKey(
                com.platform.wikibackend.search.DocType.PAGE, p.id, p.id, p.updatedAt,
                case when lower(p.title) like :q escape '\\' then 3 else 1 end)
            """ + LITE_PAGE_WHERE;

    /** 관련도순 — 제목 일치(3)가 본문 일치(1)보다 위, 같으면 최신순, 그다음 id 오름차순. */
    @Query(LITE_PAGE_KEYS
            + " order by case when lower(p.title) like :q escape '\\' then 3 else 1 end desc, p.updatedAt desc, p.id asc")
    List<com.platform.wikibackend.search.SearchKey> searchPageKeys(
            @Param("q") String q,
            @Param("spaceIds") Collection<Long> spaceIds,
            @Param("includeDrafts") boolean includeDrafts,
            @Param("anyAuthor") boolean anyAuthor,
            @Param("authorIds") Collection<Long> authorIds,
            @Param("after") java.time.Instant after,
            @Param("before") java.time.Instant before,
            @Param("anyLabel") boolean anyLabel,
            @Param("labels") Collection<String> labels,
            @Param("noHidden") boolean noHidden,
            @Param("hiddenIds") Collection<Long> hiddenIds,
            org.springframework.data.domain.Limit limit);

    /** 최신순(UPDATED_DESC) — 관련도를 보지 않는다. 같은 시각이면 나중에 만든 쪽(큰 id)이 위. */
    @Query(LITE_PAGE_KEYS + " order by p.updatedAt desc, p.id desc")
    List<com.platform.wikibackend.search.SearchKey> searchPageKeysNewest(
            @Param("q") String q,
            @Param("spaceIds") Collection<Long> spaceIds,
            @Param("includeDrafts") boolean includeDrafts,
            @Param("anyAuthor") boolean anyAuthor,
            @Param("authorIds") Collection<Long> authorIds,
            @Param("after") java.time.Instant after,
            @Param("before") java.time.Instant before,
            @Param("anyLabel") boolean anyLabel,
            @Param("labels") Collection<String> labels,
            @Param("noHidden") boolean noHidden,
            @Param("hiddenIds") Collection<Long> hiddenIds,
            org.springframework.data.domain.Limit limit);

    /** 오래된순(UPDATED_ASC). */
    @Query(LITE_PAGE_KEYS + " order by p.updatedAt asc, p.id asc")
    List<com.platform.wikibackend.search.SearchKey> searchPageKeysOldest(
            @Param("q") String q,
            @Param("spaceIds") Collection<Long> spaceIds,
            @Param("includeDrafts") boolean includeDrafts,
            @Param("anyAuthor") boolean anyAuthor,
            @Param("authorIds") Collection<Long> authorIds,
            @Param("after") java.time.Instant after,
            @Param("before") java.time.Instant before,
            @Param("anyLabel") boolean anyLabel,
            @Param("labels") Collection<String> labels,
            @Param("noHidden") boolean noHidden,
            @Param("hiddenIds") Collection<Long> hiddenIds,
            org.springframework.data.domain.Limit limit);

    /** 권한 적용 뒤의 정확한 건수 — total이 "앞쪽 후보 중 남은 수"가 아니라 진짜 수가 된다. */
    @Query("select count(p)" + LITE_PAGE_WHERE)
    long countPageMatches(
            @Param("q") String q,
            @Param("spaceIds") Collection<Long> spaceIds,
            @Param("includeDrafts") boolean includeDrafts,
            @Param("anyAuthor") boolean anyAuthor,
            @Param("authorIds") Collection<Long> authorIds,
            @Param("after") java.time.Instant after,
            @Param("before") java.time.Instant before,
            @Param("anyLabel") boolean anyLabel,
            @Param("labels") Collection<String> labels,
            @Param("noHidden") boolean noHidden,
            @Param("hiddenIds") Collection<Long> hiddenIds);

    /** 고른 한 페이지의 본문 — 스니펫 재료. 순서는 호출부가 키 순서로 맞춘다. */
    @Query("""
            select new com.platform.wikibackend.search.SearchRow(
                com.platform.wikibackend.search.DocType.PAGE,
                p.id, p.id, p.spaceId, s.key, s.name,
                cast(p.type as string), p.title, p.content, cast(null as string), p.updatedAt,
                case when lower(p.title) like :q escape '\\' then 3 else 1 end)
            from Page p join Space s on s.id = p.spaceId
            where p.id in :ids
            """)
    List<com.platform.wikibackend.search.SearchRow> findSearchRowsByIds(
            @Param("q") String q, @Param("ids") Collection<Long> ids);

    /**
     * 첨부 파일명 후보.
     *
     * 첨부에는 작성자·라벨이 없다. 그 필터가 걸린 질의는 첨부를 아예 찾지 않는다(호출부 판단) —
     * 여기서 조용히 무시하면 "작성자로 걸렀는데 남의 첨부가 나온다"가 된다. 페이지 단위 제한은
     * 소속 페이지 기준이다.
     */
    String LITE_ATTACHMENT_WHERE = """
             from Attachment a
              join Page p on p.id = a.pageId
            where p.spaceId in :spaceIds
              and a.lifecycleStatus = com.platform.wikibackend.attachment.AttachmentLifecycleStatus.CONFIRMED
              and lower(a.filename) like :q escape '\\'
              and (cast(:after as java.time.Instant) is null or a.createdAt >= :after)
              and (cast(:before as java.time.Instant) is null or a.createdAt <= :before)
              and (:noHidden = true or a.pageId not in :hiddenIds)
            """;

    String LITE_ATTACHMENT_KEYS = """
            select new com.platform.wikibackend.search.SearchKey(
                com.platform.wikibackend.search.DocType.ATTACHMENT, a.id, a.pageId, a.createdAt, 3)
            """ + LITE_ATTACHMENT_WHERE;

    /** 관련도순 — 첨부는 점수가 상수(3)라 최신순이고, 동률은 서비스 비교자처럼 id 오름차순이다. */
    @Query(LITE_ATTACHMENT_KEYS + " order by a.createdAt desc, a.id asc")
    List<com.platform.wikibackend.search.SearchKey> searchAttachmentKeys(
            @Param("q") String q,
            @Param("spaceIds") Collection<Long> spaceIds,
            @Param("after") java.time.Instant after,
            @Param("before") java.time.Instant before,
            @Param("noHidden") boolean noHidden,
            @Param("hiddenIds") Collection<Long> hiddenIds,
            org.springframework.data.domain.Limit limit);

    /** 최신순(UPDATED_DESC). */
    @Query(LITE_ATTACHMENT_KEYS + " order by a.createdAt desc, a.id desc")
    List<com.platform.wikibackend.search.SearchKey> searchAttachmentKeysNewest(
            @Param("q") String q,
            @Param("spaceIds") Collection<Long> spaceIds,
            @Param("after") java.time.Instant after,
            @Param("before") java.time.Instant before,
            @Param("noHidden") boolean noHidden,
            @Param("hiddenIds") Collection<Long> hiddenIds,
            org.springframework.data.domain.Limit limit);

    /** 오래된순(UPDATED_ASC). */
    @Query(LITE_ATTACHMENT_KEYS + " order by a.createdAt asc, a.id asc")
    List<com.platform.wikibackend.search.SearchKey> searchAttachmentKeysOldest(
            @Param("q") String q,
            @Param("spaceIds") Collection<Long> spaceIds,
            @Param("after") java.time.Instant after,
            @Param("before") java.time.Instant before,
            @Param("noHidden") boolean noHidden,
            @Param("hiddenIds") Collection<Long> hiddenIds,
            org.springframework.data.domain.Limit limit);

    @Query("select count(a)" + LITE_ATTACHMENT_WHERE)
    long countAttachmentMatches(
            @Param("q") String q,
            @Param("spaceIds") Collection<Long> spaceIds,
            @Param("after") java.time.Instant after,
            @Param("before") java.time.Instant before,
            @Param("noHidden") boolean noHidden,
            @Param("hiddenIds") Collection<Long> hiddenIds);

    @Query("""
            select new com.platform.wikibackend.search.SearchRow(
                com.platform.wikibackend.search.DocType.ATTACHMENT,
                a.id, a.pageId, p.spaceId, s.key, s.name,
                cast(null as string), cast(null as string), cast(null as string), a.filename, a.createdAt,
                3)
            from Attachment a
              join Page p on p.id = a.pageId
              join Space s on s.id = p.spaceId
            where a.id in :ids
            """)
    List<com.platform.wikibackend.search.SearchRow> findAttachmentSearchRowsByIds(@Param("ids") Collection<Long> ids);
}
