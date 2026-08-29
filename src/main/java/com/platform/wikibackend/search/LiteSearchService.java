package com.platform.wikibackend.search;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.permission.AccessScope;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.permission.PermissionClient;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 라이트 검색 — OpenSearch 없이 도는 배포의 `/api/search/graphql` 구현.
 *
 * 계약은 search-service와 같고(같은 스키마), 결과의 성질은 다르다: 형태소 분석이 없어 부분
 * 문자열로 찾고, 이 서비스가 가진 위키 데이터만 본다. 소규모 온프렘 설치를 위한 것이며,
 * 문서가 수만 건을 넘어가면 OpenSearch 배포로 옮기는 것을 전제한다.
 */
@Service
@RequiredArgsConstructor
public class LiteSearchService {

    /**
     * 권한 후필터를 태우기 전에 DB에서 가져오는 후보 상한.
     *
     * 페이지 단위 제한(W18)은 SQL로 판정할 수 없어 가져온 뒤에 거른다. 창을 무제한으로 두면
     * 제한이 하나도 없는 스페이스에서도 전량을 메모리에 올리게 된다. 창을 채웠으면 total은
     * "적어도 이만큼"이므로 totalExact=false로 사실대로 알린다.
     */
    static final int CANDIDATE_WINDOW = 500;

    private final PageRepository pageRepository;
    private final SpaceRepository spaceRepository;
    private final PermissionClient permissions;
    private final EffectivePermissionService effective;

    @Transactional(readOnly = true)
    public SearchResults search(long userId, SearchInput input) {
        long startedAt = System.nanoTime();
        String query = input.query() == null ? "" : input.query().trim();
        if (query.isEmpty()) return SearchResults.empty();

        Set<Long> spaceIds = allowedSpaces(userId, input.requestedSpaceIds());
        if (spaceIds.isEmpty()) return SearchResults.empty();

        // 날짜 파싱은 후보를 읽기 전에 끝낸다 — 형식 오류를 빈 결과로 삼키지 않는다.
        Instant after = input.updatedAfterInstant();
        Instant before = input.updatedBeforeInstant();

        List<SearchRow> candidates = candidates(input, query, spaceIds, after, before);
        boolean windowFull = candidates.size() >= CANDIDATE_WINDOW;

        List<SearchRow> visible = filterVisible(userId, candidates);
        // 두 질의를 합쳤으므로 여기서 한 번 더 정렬한다 — 각 질의의 순서만으로는 섞이지 않는다.
        visible.sort(comparator(input.normalizedSort()));

        int size = input.normalizedSize();
        int from = Math.min(input.normalizedPage() * size, visible.size());
        int to = Math.min(from + size, visible.size());
        List<SearchHit> hits = visible.subList(from, to).stream()
                .map(row -> toHit(row, query))
                .toList();

        int tookMs = (int) ((System.nanoTime() - startedAt) / 1_000_000);
        return new SearchResults(visible.size(), !windowFull, tookMs, hits);
    }

    /**
     * 정렬 — search-service와 같은 값으로 같은 순서를 내야 한다. 배포에 따라 "최신순"이 다르면
     * 사용자는 어느 쪽이 맞는지 알 수 없다.
     *
     * 마지막 기준은 항상 id다 — 같은 시각의 문서가 페이지를 넘길 때마다 순서를 바꾸면 2페이지에서
     * 1페이지의 항목이 다시 보인다. 다만 **정렬 방향을 따라간다**: 최신순에서 같은 시각이면 나중에
     * 만들어진 쪽(큰 id)이 위여야 "최신순"이라는 말과 어긋나지 않는다.
     */
    private static Comparator<SearchRow> comparator(SearchSort sort) {
        return switch (sort) {
            case UPDATED_DESC -> Comparator
                    .comparing(SearchRow::updatedAt, Comparator.reverseOrder())
                    .thenComparing(Comparator.comparingLong(SearchRow::id).reversed());
            case UPDATED_ASC -> Comparator
                    .comparing(SearchRow::updatedAt)
                    .thenComparingLong(SearchRow::id);
            case RELEVANCE -> Comparator
                    .comparingInt(SearchRow::score).reversed()
                    .thenComparing(SearchRow::updatedAt, Comparator.reverseOrder())
                    .thenComparingLong(SearchRow::id);
        };
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

    private List<SearchRow> candidates(
            SearchInput input, String query, Set<Long> spaceIds, Instant after, Instant before) {
        String like = "%" + query.toLowerCase(Locale.ROOT) + "%";
        Limit window = Limit.of(CANDIDATE_WINDOW);
        List<SearchRow> rows = new ArrayList<>();

        if (input.wants(DocType.PAGE)) {
            Set<Long> authors = input.requestedAuthorIds();
            List<String> labels = input.normalizedLabels();
            rows.addAll(pageRepository.searchPages(
                    like,
                    spaceIds,
                    input.draftsIncluded(),
                    authors.isEmpty(),
                    // in 절은 비어 있을 수 없다 — 쓰이지 않는 자리에 넣는 자리표시자다.
                    authors.isEmpty() ? Set.of(-1L) : authors,
                    after,
                    before,
                    labels.isEmpty(),
                    labels.isEmpty() ? List.of("") : labels,
                    window));
        }
        // 첨부에는 작성자·라벨이 없다 — 그 필터가 걸렸으면 첨부는 애초에 대상이 아니다.
        boolean pageOnlyFilter = !input.requestedAuthorIds().isEmpty() || !input.normalizedLabels().isEmpty();
        if (input.wants(DocType.ATTACHMENT) && !pageOnlyFilter) {
            rows.addAll(pageRepository.searchAttachments(like, spaceIds, after, before, window));
        }
        return rows;
    }

    /** PAGE는 자신, ATTACHMENT는 소속 페이지 기준으로 페이지 단위 제한(W18)을 통과해야 남는다. */
    private List<SearchRow> filterVisible(long userId, List<SearchRow> rows) {
        if (rows.isEmpty()) return new ArrayList<>();
        Set<Long> ownerIds = rows.stream()
                .map(SearchRow::ownerPageId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Collection<Page> owners = pageRepository.findAllById(ownerIds);
        Set<Long> visible = effective.viewablePageIds(userId, owners);
        return rows.stream()
                .filter(row -> row.ownerPageId() == null || visible.contains(row.ownerPageId()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
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
