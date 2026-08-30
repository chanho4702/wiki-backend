package com.platform.wikibackend.personal;

import com.platform.wikibackend.common.NotFoundException;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageStar;
import com.platform.wikibackend.domain.PageVisit;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.domain.SpaceStar;
import com.platform.wikibackend.page.dto.PageTreeItem;
import com.platform.wikibackend.permission.AccessScope;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.permission.PermissionClient;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageStarRepository;
import com.platform.wikibackend.repository.PageVisitRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import com.platform.wikibackend.repository.SpaceStarRepository;
import com.platform.wikibackend.space.SpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 별표와 최근 방문(W23) — "이 사용자가 무엇을 아껴 보는가".
 *
 * 지금까지 브라우저 localStorage에만 있었다. 회사 노트북에서 별표한 문서가 집 컴퓨터에는 없고,
 * 브라우저 데이터를 한 번 지우면 그동안 모아 둔 즐겨찾기가 통째로 사라졌다.
 *
 * 읽을 때는 **매번 지금 권한으로 다시 거른다.** 별표해 둔 뒤 권한이 회수될 수 있고, 그때 목록에
 * 제목이 남아 있으면 그것만으로 정보가 샌다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PersonalService {

    /** 최근 방문 보관 개수 — 목록으로 훑는 용도라 이보다 길면 아무도 끝까지 보지 않는다. */
    public static final int RECENT_LIMIT = 20;

    private final PageStarRepository pageStars;
    private final SpaceStarRepository spaceStars;
    private final PageVisitRepository visits;
    private final PageRepository pages;
    private final SpaceRepository spaces;
    private final SpaceService spaceService;
    private final PermissionClient permissions;
    private final EffectivePermissionService effective;

    @Transactional(readOnly = true)
    public StarsResponse stars(long userId) {
        AccessScope scope = permissions.accessibleSpaces(userId);
        List<Long> starredSpaceIds = spaceStars.findSpaceIds(userId).stream()
                .filter(scope::contains)
                .toList();
        return new StarsResponse(
                starredSpaceIds.stream().map(String::valueOf).toList(),
                visibleInOrder(userId, pageStars.findPageIds(userId)));
    }

    public void starPage(long userId, long pageId) {
        Page page = requireViewablePage(userId, pageId);
        if (!pageStars.existsByUserIdAndPageId(userId, page.getId())) {
            pageStars.save(PageStar.of(userId, page.getId()));
        }
    }

    /** 별표 해제는 볼 수 없게 된 문서에도 통해야 한다 — 아니면 목록에서 뺄 방법이 없다. */
    public void unstarPage(long userId, long pageId) {
        pageStars.deleteByUserIdAndPageId(userId, pageId);
    }

    public void starSpace(long userId, long spaceId) {
        spaceService.getForView(userId, spaceId);
        if (!spaceStars.existsByUserIdAndSpaceId(userId, spaceId)) {
            spaceStars.save(SpaceStar.of(userId, spaceId));
        }
    }

    public void unstarSpace(long userId, long spaceId) {
        spaceStars.deleteByUserIdAndSpaceId(userId, spaceId);
    }

    @Transactional(readOnly = true)
    public List<StarredPage> recent(long userId, int limit) {
        int capped = Math.max(1, Math.min(limit, RECENT_LIMIT));
        // 권한 필터로 빠지는 것이 있어 넉넉히 읽고 자른다 — 딱 맞게 읽으면 목록이 짧아진다.
        List<Long> ids = visits.findRecentPageIds(userId, Limit.of(RECENT_LIMIT));
        List<StarredPage> visible = visibleInOrder(userId, ids);
        return visible.size() <= capped ? visible : visible.subList(0, capped);
    }

    /**
     * 방문 기록 — 페이지 조회수 증가와 같은 요청에서 부른다(왕복을 늘리지 않는다).
     *
     * 권한은 호출부(조회 경로)가 이미 봤다. 여기서 다시 보면 조회수 경로와 판정이 갈릴 수 있다.
     */
    public void recordVisit(long userId, long pageId) {
        visits.findByUserIdAndPageId(userId, pageId)
                .ifPresentOrElse(PageVisit::touch, () -> visits.save(PageVisit.of(userId, pageId)));
        trim(userId);
    }

    /** 상한을 넘으면 오래된 것부터 버린다 — 방문 기록은 무한히 자라야 할 이유가 없다. */
    private void trim(long userId) {
        List<PageVisit> all = visits.findAllByUser(userId);
        if (all.size() <= RECENT_LIMIT) return;
        visits.deleteAll(all.subList(RECENT_LIMIT, all.size()));
    }

    /**
     * 주어진 순서를 지키면서 지금 볼 수 있는 것만 남긴다.
     *
     * 스페이스 권한(org)과 페이지 단위 제한(W18)을 모두 통과해야 한다 — 한쪽만 보면 그 경로가
     * 누출구가 된다. 지워진 페이지는 조회에서 아예 빠진다(@SQLRestriction).
     */
    private List<StarredPage> visibleInOrder(long userId, List<Long> orderedIds) {
        if (orderedIds.isEmpty()) return List.of();
        List<Page> found = pages.findAllById(orderedIds);
        AccessScope scope = permissions.accessibleSpaces(userId);
        List<Page> inAllowedSpaces = found.stream()
                .filter(p -> scope.contains(p.getSpaceId()))
                .toList();
        Set<Long> visible = effective.viewablePageIds(userId, inAllowedSpaces);

        Map<Long, Page> byId = inAllowedSpaces.stream()
                .filter(p -> visible.contains(p.getId()))
                .collect(java.util.stream.Collectors.toMap(Page::getId, Function.identity()));
        Map<Long, Space> spaceById = spaces.findAllById(
                        byId.values().stream().map(Page::getSpaceId).distinct().toList()).stream()
                .collect(java.util.stream.Collectors.toMap(Space::getId, Function.identity()));

        return orderedIds.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .map(p -> new StarredPage(
                        PageTreeItem.from(p),
                        String.valueOf(p.getSpaceId()),
                        spaceById.containsKey(p.getSpaceId()) ? spaceById.get(p.getSpaceId()).getName() : null))
                .toList();
    }

    private Page requireViewablePage(long userId, long pageId) {
        Page page = pages.findById(pageId)
                .orElseThrow(() -> new NotFoundException("페이지 없음: " + pageId));
        spaceService.getForView(userId, page.getSpaceId());
        effective.requireView(userId, page);
        return page;
    }

    /**
     * 별표·최근 목록의 한 줄. 스페이스 이름을 함께 준다 — 목록이 스페이스를 가로지르므로,
     * 이름이 없으면 같은 제목의 문서가 여럿일 때 구분할 수 없다.
     */
    public record StarredPage(PageTreeItem page, String spaceId, String spaceName) {
    }

    public record StarsResponse(List<String> spaceIds, List<StarredPage> pages) {
    }

    static Comparator<Page> byIdAsc() {
        return Comparator.comparing(Page::getId);
    }
}
