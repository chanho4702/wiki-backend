package com.platform.wikibackend.feed;

import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.page.dto.FeedCandidate;
import com.platform.wikibackend.page.dto.PageTreeItem;
import com.platform.wikibackend.permission.AccessScope;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.permission.PermissionClient;
import com.platform.wikibackend.personal.PersonalService.StarredPage;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 스페이스를 가로지르는 문서 피드 — 사람 프로필의 최근 작업(F-07)과 홈의 인기·팔로잉.
 *
 * 가시성은 새로 짜지 않는다. 스페이스 경계는 org의 접근 범위({@link PermissionClient#accessibleSpaces})로
 * SQL에서 자르고, 페이지 단위(VIEW 제한 + 초안 정책 — 작성자·스페이스 ADMIN·전역 ADMIN, 초안 조상 아래
 * 하위도 함께 가림)는 {@link EffectivePermissionService#viewableIds}가 판정한다 — 트리·검색·알림과 같은 함수다.
 * org 불능(UNAVAILABLE/DEADLINE)은 그 판정에서 503으로 올라간다(빈 피드로 삼키지 않는다).
 *
 * 상한이 있는 목록이라 "읽고 나서 거르면" 앞쪽 후보가 전부 볼 수 없는 문서일 때 결과가 비어 버린다.
 * 같은 정렬로 다음 묶음을 이어 읽어 limit을 채우되, {@link #SCAN_LIMIT}에서 멈춘다(요청이 끝없이 돌지 않게).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeedService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;
    /** 인기 피드의 기간 창 — 누적 조회수라 창이 없으면 오래전에 많이 읽힌 문서가 영원히 위를 차지한다. */
    public static final Duration POPULAR_WINDOW = Duration.ofDays(90);
    static final int BATCH = 100;
    static final int SCAN_LIMIT = 1_000;

    private final PageRepository pages;
    private final SpaceRepository spaces;
    private final PermissionClient permissions;
    private final EffectivePermissionService effective;

    /** 그 사람이 만들었거나 마지막으로 고친 문서(초안 포함, 호출자가 볼 수 있는 것만) — 최근 수정순. */
    public List<StarredPage> personPages(long callerId, long personId, int limit) {
        return collect(callerId, limit, (scope, page) ->
                        pages.findFeedByPerson(personId, scope.all(), inList(scope), page))
                .stream()
                .map(r -> new StarredPage(r.page(), r.spaceId(), r.spaceName()))
                .toList();
    }

    /** 인기 — 최근 {@link #POPULAR_WINDOW} 안에 수정된 게시 문서를 누적 조회수순으로. */
    public List<FeedPage> popular(long callerId, int limit) {
        java.time.Instant since = java.time.Instant.now().minus(POPULAR_WINDOW);
        return collect(callerId, limit, (scope, page) ->
                pages.findFeedPopular(since, scope.all(), inList(scope), page));
    }

    /** 팔로잉 — 구독한 스페이스·문서의 게시 문서, 최근 수정순. */
    public List<FeedPage> following(long callerId, int limit) {
        return collect(callerId, limit, (scope, page) ->
                pages.findFeedFollowing(callerId, scope.all(), inList(scope), page));
    }

    /* ── 내부 ────────────────────────────────────────────── */

    @FunctionalInterface
    private interface Fetch {
        List<FeedCandidate> apply(AccessScope scope, Pageable page);
    }

    private List<FeedPage> collect(long callerId, int limit, Fetch fetch) {
        int want = Math.max(1, Math.min(limit, MAX_LIMIT));
        AccessScope scope = permissions.accessibleSpaces(callerId);
        if (!scope.all() && scope.spaceIds().isEmpty()) return List.of();

        List<FeedCandidate> out = new ArrayList<>();
        for (int pageNo = 0; pageNo * BATCH < SCAN_LIMIT && out.size() < want; pageNo++) {
            List<FeedCandidate> batch = fetch.apply(scope, PageRequest.of(pageNo, BATCH));
            if (batch.isEmpty()) break;
            Set<Long> allowed = effective.viewableIds(callerId, batch.stream().map(FeedCandidate::id).toList());
            for (FeedCandidate c : batch) {
                if (allowed.contains(c.id())) {
                    out.add(c);
                    if (out.size() == want) break;
                }
            }
            if (batch.size() < BATCH) break; // 더 읽을 후보가 없다
        }
        return toRows(out);
    }

    /** 스페이스 이름은 결과 전체에 한 번 — 행마다 묻지 않는다. */
    private List<FeedPage> toRows(List<FeedCandidate> found) {
        if (found.isEmpty()) return List.of();
        Map<Long, String> nameOf = spaces.findAllById(
                        found.stream().map(FeedCandidate::spaceId).distinct().toList()).stream()
                .collect(Collectors.toMap(Space::getId, Space::getName, (a, b) -> a));
        return found.stream()
                .map(c -> new FeedPage(c.item(), String.valueOf(c.spaceId()), nameOf.get(c.spaceId()),
                        c.viewCount() == null ? 0L : c.viewCount()))
                .toList();
    }

    /** GLOBAL 보유자는 목록이 없다 — in 절이 비면 문법이 깨지므로 자리만 채운다(allSpaces=true라 보지 않는다). */
    private static Collection<Long> inList(AccessScope scope) {
        return scope.all() ? List.of(-1L) : scope.spaceIds();
    }

    /**
     * 피드 한 줄 — `/api/wiki/drafts`·`/recent`와 같은 {page, spaceId, spaceName}에 누적 조회수를 더했다.
     */
    public record FeedPage(PageTreeItem page, String spaceId, String spaceName, long viewCount) {
    }
}
