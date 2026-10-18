package com.platform.wikibackend.watch;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.AccessScope;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.permission.PermissionClient;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageWatchRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import com.platform.wikibackend.repository.SpaceWatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 내가 구독한 문서·스페이스 목록과 일괄 해제(F-06, 2026-10-18).
 *
 * 구독은 할 수 있는데 무엇을 구독했는지 볼 곳이 없었다. 목록은 읽을 때마다 지금 권한으로 거른다 —
 * 권한을 잃은 문서·스페이스는 행은 남기되(해제할 수 있어야 하므로) 제목을 가린다(hidden=true, title=null).
 * 휴지통으로 간 문서는 목록에서 빠진다(조회 경로의 @SQLRestriction).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class WatchListService {

    private final PageWatchRepository pageWatches;
    private final SpaceWatchRepository spaceWatches;
    private final PageRepository pages;
    private final SpaceRepository spaces;
    private final PermissionClient permissions;
    private final EffectivePermissionService effective;

    @Transactional(readOnly = true)
    public WatchesResponse list(long userId) {
        AccessScope scope = permissions.accessibleSpaces(userId);

        List<Long> pageIds = pageWatches.findPageIdsByUser(userId);
        Map<Long, Page> pageById = pages.findAllById(pageIds).stream()
                .collect(Collectors.toMap(Page::getId, Function.identity()));
        List<Page> inScope = pageById.values().stream().filter(p -> scope.contains(p.getSpaceId())).toList();
        Set<Long> visible = effective.viewablePageIds(userId, inScope);

        List<Long> spaceIds = spaceWatches.findSpaceIdsByUser(userId);
        Set<Long> allSpaceIds = new java.util.HashSet<>(spaceIds);
        pageById.values().forEach(p -> allSpaceIds.add(p.getSpaceId()));
        Map<Long, Space> spaceById = spaces.findAllById(allSpaceIds).stream()
                .collect(Collectors.toMap(Space::getId, Function.identity()));

        List<WatchedPage> watchedPages = pageIds.stream()
                .map(pageById::get)
                .filter(java.util.Objects::nonNull)
                .map(p -> {
                    boolean shown = visible.contains(p.getId());
                    Space s = spaceById.get(p.getSpaceId());
                    return new WatchedPage(p.getId(), p.getSpaceId(),
                            shown ? p.getTitle() : null,
                            shown && s != null ? s.getName() : null,
                            !shown);
                })
                .toList();
        List<WatchedSpace> watchedSpaces = spaceIds.stream()
                .filter(spaceById::containsKey)
                .map(id -> {
                    boolean shown = scope.contains(id);
                    return new WatchedSpace(id, shown ? spaceById.get(id).getName() : null, !shown);
                })
                .toList();
        return new WatchesResponse(watchedPages, watchedSpaces);
    }

    /** 일괄 해제 — 볼 수 없게 된 대상도 해제된다(아니면 목록에서 뺄 방법이 없다). 본인 행만. */
    public void unwatch(long userId, Collection<Long> pageIds, Collection<Long> spaceIds) {
        if (pageIds != null && !pageIds.isEmpty()) pageWatches.deleteByUserIdAndPageIdIn(userId, pageIds);
        if (spaceIds != null && !spaceIds.isEmpty()) spaceWatches.deleteByUserIdAndSpaceIdIn(userId, spaceIds);
    }

    /** hidden이면 지금 볼 수 없는 문서다 — 제목·스페이스 이름을 가린다. */
    public record WatchedPage(Long pageId, Long spaceId, String title, String spaceName, boolean hidden) {
    }

    public record WatchedSpace(Long spaceId, String name, boolean hidden) {
    }

    public record WatchesResponse(List<WatchedPage> pages, List<WatchedSpace> spaces) {
    }
}
