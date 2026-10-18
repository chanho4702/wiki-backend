package com.platform.wikibackend.search;

import com.platform.wikibackend.permission.AccessScope;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.permission.PermissionClient;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 라이트 검색 — OpenSearch 없이 도는 배포의 `/api/search/graphql` 구현.
 *
 * 계약은 search-service와 같고(같은 스키마), 결과의 성질은 다르다: 형태소 분석이 없어 부분
 * 문자열로 찾고, 이 서비스가 가진 위키 데이터만 본다. 소규모 온프렘 설치를 위한 것이며,
 * 문서가 수만 건을 넘어가면 OpenSearch 배포로 옮기는 것을 전제한다.
 *
 * 권한(2026-10-16 개편): 예전에는 유형별 후보를 500건으로 자른 **뒤에** 페이지 제한(W18)을
 * 걸렀다. 앞쪽 500건이 전부 제한 문서면 그 뒤의 볼 수 있는 문서가 있어도 total=0이었고, 다음
 * 페이지로도 닿을 수 없었다. 지금은 사용자가 볼 수 없는 페이지 집합을 먼저 구해 SQL이 처음부터
 * 제외한다 — 건수·정렬·페이지 경계가 모두 권한 적용 뒤의 값이라 total이 정확하다.
 */
@Service
public class LiteSearchService {

    /**
     * 숨길 페이지 id를 SQL 파라미터로 넘기는 상한(기본). 이보다 많으면(제한 문서가 수천 건인 설치)
     * 바인드 변수 수가 드라이버 한도로 다가가므로 scanLimit까지 가벼운 키만 읽어 메모리에서 거른다.
     */
    static final int HIDDEN_INLINE_LIMIT = 1_000;

    /**
     * 메모리 거르기 경로에서 유형별로 읽는 키(본문 없음) 상한(기본). 이 경로에서만 쓰이고, 넘치면
     * total은 "적어도 이만큼"이므로 totalExact=false로 사실대로 알린다(화면이 "N건 이상"으로 표시).
     */
    static final int SCAN_LIMIT = 10_000;

    private final PageRepository pageRepository;
    private final SpaceRepository spaceRepository;
    private final PermissionClient permissions;
    private final EffectivePermissionService effective;
    private final int hiddenInlineLimit;
    private final int scanLimit;

    /** 상한은 설정으로도 바꿀 수 있다 — 테스트가 작은 값으로 상한 경계(절단·하한 total)를 실제로 태운다. */
    public LiteSearchService(PageRepository pageRepository,
                             SpaceRepository spaceRepository,
                             PermissionClient permissions,
                             EffectivePermissionService effective,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${platform.wiki.search.lite.hidden-inline-limit:" + HIDDEN_INLINE_LIMIT + "}")
                             int hiddenInlineLimit,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${platform.wiki.search.lite.scan-limit:" + SCAN_LIMIT + "}")
                             int scanLimit) {
        this.pageRepository = pageRepository;
        this.spaceRepository = spaceRepository;
        this.permissions = permissions;
        this.effective = effective;
        this.hiddenInlineLimit = hiddenInlineLimit;
        this.scanLimit = scanLimit;
    }

    @Transactional(readOnly = true)
    public SearchResults search(long userId, SearchInput input) {
        long startedAt = System.nanoTime();
        String query = input.query() == null ? "" : input.query().trim();
        if (query.isEmpty()) return SearchResults.empty();

        Set<Long> spaceIds = allowedSpaces(userId, input.requestedSpaceIds());
        if (spaceIds.isEmpty()) return SearchResults.empty();

        // 날짜 파싱은 후보를 읽기 전에 끝낸다 — 형식 오류를 빈 결과로 삼키지 않는다.
        Criteria c = new Criteria(input, com.platform.wikibackend.common.LikePatterns.contains(query.toLowerCase(Locale.ROOT)), spaceIds,
                input.updatedAfterInstant(), input.updatedBeforeInstant());

        Comparator<SearchKey> order = comparator(input.normalizedSort());
        int size = input.normalizedSize();
        long from = (long) input.normalizedPage() * size;

        // 실시간 판정 — 제한 회수·이동이 바로 다음 검색에 반영돼야 한다(캐시하지 않는다).
        Set<Long> hidden = effective.hiddenPageIds(userId, spaceIds);
        for (int attempt = 0; attempt < STABLE_ATTEMPTS; attempt++) {
            Window window = hidden.size() <= hiddenInlineLimit
                    ? exactWindow(c, hidden, order, from, size)
                    : scannedWindow(c, hidden, order);
            List<SearchHit> hits = recheck(userId, spaceIds, hydrate(slice(window.ordered(), from, size), c.like()))
                    .stream()
                    .map(row -> toHit(row, query))
                    .toList();
            // 질의를 마친 **뒤에** 숨김 집합을 다시 읽는다. 그 사이 커밋된 제한은 여기서 보인다 — 건수(total)는
            // 이미 그 문서를 셌을 수 있으므로(제한 직후 저장된 기밀 본문에만 있는 검색어라면 "있다"는 사실
            // 자체가 샌다), 새로 숨길 것이 생겼으면 합집합으로 다시 센다. 본문 쪽은 recheck가 막는다.
            Set<Long> after = effective.hiddenPageIds(userId, spaceIds);
            if (hidden.containsAll(after)) {
                int tookMs = (int) ((System.nanoTime() - startedAt) / 1_000_000);
                return new SearchResults((int) Math.min(window.total(), Integer.MAX_VALUE), window.exact(), tookMs, hits);
            }
            Set<Long> union = new java.util.HashSet<>(hidden);
            union.addAll(after);
            hidden = union;
        }
        // 제한이 계속 바뀌는 중이면 확정된 답을 낼 수 없다 — 새는 쪽보다 비우는 쪽을 택하고 사실대로 알린다.
        int tookMs = (int) ((System.nanoTime() - startedAt) / 1_000_000);
        return new SearchResults(0, false, tookMs, List.of());
    }

    /** 숨김 집합이 질의 전후로 같아질 때까지의 재시도 상한. 제한을 연달아 바꾸는 중이 아니면 첫 번에 끝난다. */
    static final int STABLE_ATTEMPTS = 3;

    /** 요청 하나의 조건 묶음 — 유형별 질의 세 갈래(키·건수·본문)가 같은 값을 쓴다. */
    private record Criteria(SearchInput input, String like, Set<Long> spaceIds, Instant after, Instant before) {
        boolean pages() {
            return input.wants(DocType.PAGE);
        }

        /** 첨부에는 작성자·라벨이 없다 — 그 필터가 걸렸으면 첨부는 애초에 대상이 아니다. */
        boolean attachments() {
            boolean pageOnlyFilter = !input.requestedAuthorIds().isEmpty() || !input.normalizedLabels().isEmpty();
            return input.wants(DocType.ATTACHMENT) && !pageOnlyFilter;
        }
    }

    /**
     * total: 권한 적용 뒤 전체 건수. ordered: 그중 요청한 페이지를 자를 수 있을 만큼의 앞부분
     * (요청 정렬 순). exact가 false면 total은 하한이다.
     */
    private record Window(long total, boolean exact, List<SearchKey> ordered) {}

    /** 숨길 집합을 SQL에 넘겨 정확히 센다. 키는 요청 페이지 끝까지만 읽는다(앞부분 + 한 페이지). */
    private Window exactWindow(Criteria c, Set<Long> hidden, Comparator<SearchKey> order, long from, int size) {
        long total = 0;
        if (c.pages()) total += countPages(c, hidden);
        if (c.attachments()) total += countAttachments(c, hidden);
        if (from >= total || size == 0) return new Window(total, true, List.of());
        // 두 유형을 섞으므로 각 유형에서 "끝 위치"까지 읽어야 병합 뒤 자를 수 있다.
        Limit need = Limit.of((int) Math.min(from + size, total));
        List<SearchKey> keys = new ArrayList<>();
        if (c.pages()) keys.addAll(pageKeys(c, hidden, need));
        if (c.attachments()) keys.addAll(attachmentKeys(c, hidden, need));
        keys.sort(order);
        return new Window(total, true, keys);
    }

    /**
     * 숨길 문서가 너무 많은 설치 — 키(본문 없음)를 유형별 {@link #SCAN_LIMIT}까지 읽고 메모리에서
     * 거른다. 앞쪽이 전부 제한 문서여도 상한까지는 뒤를 계속 본다(예전 500건 후필터와 다른 점).
     */
    private Window scannedWindow(Criteria c, Set<Long> hidden, Comparator<SearchKey> order) {
        Limit probe = Limit.of(scanLimit + 1);
        List<List<SearchKey>> streams = new ArrayList<>();
        if (c.pages()) streams.add(pageKeys(c, Set.of(), probe));
        if (c.attachments()) streams.add(attachmentKeys(c, Set.of(), probe));

        boolean exact = true;
        SearchKey boundary = null; // 잘린 유형의 마지막 키 — 그 뒤는 다른 유형도 믿을 수 없다
        List<SearchKey> merged = new ArrayList<>();
        for (List<SearchKey> stream : streams) {
            List<SearchKey> kept = stream;
            if (stream.size() > scanLimit) {
                exact = false;
                kept = stream.subList(0, scanLimit);
                SearchKey last = kept.get(kept.size() - 1);
                if (boundary == null || order.compare(last, boundary) < 0) boundary = last;
            }
            for (SearchKey key : kept) {
                if (key.ownerPageId() == null || !hidden.contains(key.ownerPageId())) merged.add(key);
            }
        }
        merged.sort(order);
        if (boundary != null) {
            SearchKey cut = boundary;
            merged.removeIf(key -> order.compare(key, cut) > 0);
        }
        return new Window(merged.size(), exact, merged);
    }

    private static List<SearchKey> slice(List<SearchKey> ordered, long from, int size) {
        if (from >= ordered.size()) return List.of();
        int start = (int) from;
        return ordered.subList(start, Math.min(start + size, ordered.size()));
    }

    /** 고른 한 페이지만 본문(스니펫 재료)을 읽고, 키 순서를 그대로 지킨다. */
    private List<SearchRow> hydrate(List<SearchKey> slice, String like) {
        if (slice.isEmpty()) return List.of();
        List<Long> pageIds = slice.stream().filter(k -> k.docType() == DocType.PAGE).map(SearchKey::id).toList();
        List<Long> attachmentIds = slice.stream().filter(k -> k.docType() == DocType.ATTACHMENT).map(SearchKey::id).toList();
        Map<String, SearchRow> byKey = new HashMap<>();
        if (!pageIds.isEmpty()) {
            for (SearchRow row : pageRepository.findSearchRowsByIds(like, pageIds)) byKey.put(keyOf(row.docType(), row.id()), row);
        }
        if (!attachmentIds.isEmpty()) {
            for (SearchRow row : pageRepository.findAttachmentSearchRowsByIds(attachmentIds)) byKey.put(keyOf(row.docType(), row.id()), row);
        }
        // 키를 읽은 뒤 같은 트랜잭션 안에서 지워진 행은 조용히 빠진다(빈 자리로 내보내지 않는다).
        return slice.stream().map(k -> byKey.get(keyOf(k.docType(), k.id()))).filter(Objects::nonNull).toList();
    }

    /**
     * 본문을 읽은 **뒤에** 그 한 쪽만 권한을 다시 판정한다(check-after-read).
     *
     * 숨김 집합은 요청 초반에 구했다. 그 사이 관리자가 제한을 걸거나 비공개 스페이스로 옮긴 뒤 기밀
     * 본문을 저장하면, READ COMMITTED에서는 이어지는 본문 조회가 새 본문을 본다 — 옛 판정으로 새
     * 기밀을 내보내게 된다. 본문을 먼저 읽고 판정을 나중에 하면, 그 본문이 쓰이기 전에 커밋된 제한·이동은
     * 판정이 반드시 본다. 한 쪽(최대 100건)이라 조상 폐포 2쿼리면 된다.
     * 스페이스는 본문과 같은 행에서 읽은 값으로 확인한다(이동은 그 행의 space_id를 바꾼다).
     */
    private List<SearchRow> recheck(long userId, Set<Long> spaceIds, List<SearchRow> rows) {
        if (rows.isEmpty()) return rows;
        Set<Long> owners = rows.stream()
                .map(SearchRow::ownerPageId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Set<Long> viewable = effective.viewableIds(userId, owners);
        return rows.stream()
                .filter(row -> spaceIds.contains(row.spaceId()))
                .filter(row -> row.ownerPageId() != null && viewable.contains(row.ownerPageId()))
                .toList();
    }

    private static String keyOf(DocType type, long id) {
        return type.name() + ":" + id;
    }

    /* ── 유형별 질의 — in 절은 비어 있을 수 없어 쓰이지 않는 자리에 자리표시자를 넣는다 ── */

    private static Collection<Long> idsOrPlaceholder(Set<Long> ids) {
        return ids.isEmpty() ? Set.of(-1L) : ids;
    }

    private long countPages(Criteria c, Set<Long> hidden) {
        PageArgs a = new PageArgs(c);
        return pageRepository.countPageMatches(c.like(), c.spaceIds(), a.drafts, a.anyAuthor, a.authorIds,
                c.after(), c.before(), a.anyLabel, a.labels, hidden.isEmpty(), idsOrPlaceholder(hidden));
    }

    private List<SearchKey> pageKeys(Criteria c, Set<Long> hidden, Limit limit) {
        PageArgs a = new PageArgs(c);
        boolean noHidden = hidden.isEmpty();
        Collection<Long> hiddenIds = idsOrPlaceholder(hidden);
        // 후보는 **요청 정렬과 같은 순서**로 잘라야 한다. 관련도순으로 자른 뒤 메모리에서 오래된순으로
        // 다시 세우면 "가장 최신 N건 중 오래된 순"이 된다(2026-09-13 결함).
        return switch (c.input().normalizedSort()) {
            case RELEVANCE -> pageRepository.searchPageKeys(c.like(), c.spaceIds(), a.drafts, a.anyAuthor,
                    a.authorIds, c.after(), c.before(), a.anyLabel, a.labels, noHidden, hiddenIds, limit);
            case UPDATED_DESC -> pageRepository.searchPageKeysNewest(c.like(), c.spaceIds(), a.drafts, a.anyAuthor,
                    a.authorIds, c.after(), c.before(), a.anyLabel, a.labels, noHidden, hiddenIds, limit);
            case UPDATED_ASC -> pageRepository.searchPageKeysOldest(c.like(), c.spaceIds(), a.drafts, a.anyAuthor,
                    a.authorIds, c.after(), c.before(), a.anyLabel, a.labels, noHidden, hiddenIds, limit);
        };
    }

    private long countAttachments(Criteria c, Set<Long> hidden) {
        return pageRepository.countAttachmentMatches(c.like(), c.spaceIds(), c.after(), c.before(),
                hidden.isEmpty(), idsOrPlaceholder(hidden));
    }

    private List<SearchKey> attachmentKeys(Criteria c, Set<Long> hidden, Limit limit) {
        boolean noHidden = hidden.isEmpty();
        Collection<Long> hiddenIds = idsOrPlaceholder(hidden);
        return switch (c.input().normalizedSort()) {
            case RELEVANCE -> pageRepository.searchAttachmentKeys(
                    c.like(), c.spaceIds(), c.after(), c.before(), noHidden, hiddenIds, limit);
            case UPDATED_DESC -> pageRepository.searchAttachmentKeysNewest(
                    c.like(), c.spaceIds(), c.after(), c.before(), noHidden, hiddenIds, limit);
            case UPDATED_ASC -> pageRepository.searchAttachmentKeysOldest(
                    c.like(), c.spaceIds(), c.after(), c.before(), noHidden, hiddenIds, limit);
        };
    }

    /** 페이지 질의의 작성자·라벨·초안 인자. */
    private static final class PageArgs {
        final boolean drafts;
        final boolean anyAuthor;
        final Collection<Long> authorIds;
        final boolean anyLabel;
        final List<String> labels;

        PageArgs(Criteria c) {
            Set<Long> authors = c.input().requestedAuthorIds();
            List<String> requested = c.input().normalizedLabels();
            drafts = c.input().draftsIncluded();
            anyAuthor = authors.isEmpty();
            authorIds = anyAuthor ? Set.of(-1L) : authors;
            anyLabel = requested.isEmpty();
            labels = anyLabel ? List.of("") : requested;
        }
    }

    /**
     * 정렬 — search-service와 같은 값으로 같은 순서를 내야 한다. 배포에 따라 "최신순"이 다르면
     * 사용자는 어느 쪽이 맞는지 알 수 없다. PageRepository의 키 질의 ORDER BY와도 같아야 한다 —
     * SQL이 자른 앞부분을 이 순서로 병합해 페이지를 자르기 때문이다.
     *
     * 시각 다음 기준은 id다 — 같은 시각의 문서가 페이지를 넘길 때마다 순서를 바꾸면 2페이지에서
     * 1페이지의 항목이 다시 보인다. 최신순에서 같은 시각이면 나중에 만들어진 쪽(큰 id)이 위여야
     * "최신순"이라는 말과 어긋나지 않는다. 마지막은 유형(페이지 먼저) — 페이지와 첨부는 다른 표라
     * id가 겹칠 수 있다.
     */
    static Comparator<SearchKey> comparator(SearchSort sort) {
        Comparator<SearchKey> base = switch (sort) {
            case UPDATED_DESC -> Comparator
                    .comparing(SearchKey::updatedAt, Comparator.reverseOrder())
                    .thenComparing(Comparator.comparingLong(SearchKey::id).reversed());
            case UPDATED_ASC -> Comparator
                    .comparing(SearchKey::updatedAt)
                    .thenComparingLong(SearchKey::id);
            case RELEVANCE -> Comparator
                    .comparingInt(SearchKey::score).reversed()
                    .thenComparing(SearchKey::updatedAt, Comparator.reverseOrder())
                    .thenComparingLong(SearchKey::id);
        };
        return base.thenComparing(SearchKey::docType);
    }

    /**
     * 요청한 스페이스와 접근 가능한 스페이스의 교집합. 요청이 없으면 접근 가능한 전부다.
     *
     * GLOBAL 보유자(all=true)는 스페이스 목록을 따로 갖고 있지 않아 여기서 조달한다 — 그래야
     * "요청 없음 + 전역 권한"에서도 in 절에 넣을 구체적인 id가 생긴다.
     */
    private Set<Long> allowedSpaces(long userId, Set<Long> requested) {
        AccessScope scope = permissions.accessibleSpaces(userId);
        if (!requested.isEmpty()) {
            return requested.stream().filter(scope::contains).collect(java.util.stream.Collectors.toSet());
        }
        if (!scope.all()) return scope.spaceIds();
        return spaceRepository.findAll().stream().map(s -> s.getId()).collect(java.util.stream.Collectors.toSet());
    }

    private static SearchHit toHit(SearchRow row, String query) {
        return new SearchHit(
                String.valueOf(row.id()),
                row.docType(),
                String.valueOf(row.spaceId()),
                row.spaceKey(),
                row.spaceName(),
                row.docType() == DocType.ATTACHMENT ? String.valueOf(row.ownerPageId()) : null,
                row.pageType(),
                row.title(),
                row.filename(),
                Snippets.highlights(query, row.title(), row.content(), row.filename()),
                row.updatedAt() == null ? null : row.updatedAt().toString(),
                row.score());
    }
}
