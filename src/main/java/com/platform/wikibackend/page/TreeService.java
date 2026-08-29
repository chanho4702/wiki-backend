package com.platform.wikibackend.page;

import com.platform.wikibackend.common.NotFoundException;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.page.dto.PageNode;
import com.platform.wikibackend.page.dto.PageTreeItem;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.space.SpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 지연 트리(2026-08-28).
 *
 * 지금까지 프론트는 스페이스의 전 페이지를 한 번에 받아 트리·링크 해석·자동완성·브레드크럼을
 * 전부 그 배열 하나로 처리했다. 문서가 수만 건이 되면 사이드바 한 번 여는 데 전량이 실린다.
 *
 * 여기 있는 조회는 전부 "지금 화면에 필요한 만큼"이다 — 직계 자식, 조상 체인, 제목 조회,
 * 제목 검색, 후손 폐포. 어느 경로든 effective VIEW로 걸러 나간다(설계 §3.2 — 한 경로라도
 * 우회하면 그 경로가 누출구가 된다).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TreeService {

    /** 제목 검색·조회의 상한 — 자동완성과 필터가 목적이라 전량을 줄 이유가 없다. */
    public static final int SEARCH_LIMIT = 50;
    /** 한 번에 조회할 수 있는 제목 수 — `[[링크]]`가 아무리 많아도 한 문서 안이다. */
    public static final int LOOKUP_LIMIT = 200;

    private final PageRepository pages;
    private final SpaceService spaces;
    private final EffectivePermissionService effective;

    /** 직계 자식(parentId 생략 = 루트). */
    public List<PageNode> children(long userId, long spaceId, Long parentId) {
        spaces.getForView(userId, spaceId);
        if (parentId != null) {
            Page parent = pages.findById(parentId)
                    .orElseThrow(() -> new NotFoundException("페이지 없음: " + parentId));
            if (!Objects.equals(parent.getSpaceId(), spaceId)) {
                throw new IllegalArgumentException("부모 페이지가 이 스페이스에 없습니다");
            }
            effective.requireView(userId, parent);
        }
        return withChildCounts(visible(userId, pages.findChildren(spaceId, parentId)));
    }

    /**
     * 루트→부모 순서의 조상 체인(자기 자신 제외). 브레드크럼과 "깊은 링크로 들어왔을 때
     * 그 자리까지 트리 펼치기"가 쓴다.
     */
    public List<PageNode> ancestors(long userId, long pageId) {
        Page page = pages.findById(pageId)
                .orElseThrow(() -> new NotFoundException("페이지 없음: " + pageId));
        spaces.require(userId, page.getSpaceId(), WikiAction.VIEW);
        effective.requireView(userId, page);

        Map<Long, Long> parentOf = new HashMap<>();
        for (Object[] row : pages.findAncestorClosure(List.of(pageId))) {
            parentOf.put(((Number) row[0]).longValue(),
                    row[1] == null ? null : ((Number) row[1]).longValue());
        }
        List<Long> chain = new ArrayList<>();
        Set<Long> visited = new LinkedHashSet<>(List.of(pageId));
        Long cursor = parentOf.get(pageId);
        while (cursor != null && visited.add(cursor)) {
            chain.add(cursor);
            cursor = parentOf.get(cursor);
        }
        java.util.Collections.reverse(chain); // 루트가 먼저
        if (chain.isEmpty()) return List.of();

        Map<Long, PageTreeItem> byId = new HashMap<>();
        for (PageTreeItem item : pages.findTreeItemsByIds(chain)) byId.put(item.id(), item);
        List<PageTreeItem> ordered = chain.stream().map(byId::get).filter(Objects::nonNull).toList();
        return withChildCounts(visible(userId, ordered));
    }

    /**
     * 여러 페이지의 조상 경로를 한 번에 — 검색 결과가 "어디에 있는 문서인지" 보여줄 때 쓴다.
     *
     * 스페이스를 가로지른다(검색 결과가 그렇다). 폐포는 한 번이고, 볼 수 없는 문서는 아예 답에
     * 넣지 않는다 — 경로만 흘려도 제한된 문서의 위치와 제목이 새기 때문이다.
     *
     * 조상 중 볼 수 없는 것이 섞이는 경우는 없다: VIEW 제한은 하위로 상속되므로 자신이 보이면
     * 조상도 보인다.
     */
    public List<com.platform.wikibackend.page.dto.PagePath> paths(long userId, Collection<Long> ids) {
        List<Long> wanted = ids.stream().filter(Objects::nonNull).distinct().limit(LOOKUP_LIMIT).toList();
        if (wanted.isEmpty()) return List.of();

        List<Page> targets = pages.findAllById(wanted);
        // 스페이스 VIEW부터 — 페이지 단위 판정은 스페이스 권한을 전제한다.
        Map<Long, Boolean> spaceAllowed = new HashMap<>();
        List<Page> inAllowedSpaces = targets.stream()
                .filter(p -> spaceAllowed.computeIfAbsent(
                        p.getSpaceId(), sid -> spaces.canView(userId, sid)))
                .toList();
        Set<Long> visible = effective.viewablePageIds(userId, inAllowedSpaces);
        List<Long> allowed = inAllowedSpaces.stream()
                .map(Page::getId).filter(visible::contains).toList();
        if (allowed.isEmpty()) return List.of();

        Map<Long, Long> parentOf = new HashMap<>();
        for (Object[] row : pages.findAncestorClosure(allowed)) {
            parentOf.put(((Number) row[0]).longValue(),
                    row[1] == null ? null : ((Number) row[1]).longValue());
        }
        Set<Long> chainIds = new LinkedHashSet<>();
        Map<Long, List<Long>> chains = new HashMap<>();
        for (Long id : allowed) {
            List<Long> chain = new ArrayList<>();
            Set<Long> seen = new LinkedHashSet<>(List.of(id));
            Long cursor = parentOf.get(id);
            while (cursor != null && seen.add(cursor)) {
                chain.add(cursor);
                cursor = parentOf.get(cursor);
            }
            java.util.Collections.reverse(chain); // 루트가 먼저
            chains.put(id, chain);
            chainIds.addAll(chain);
        }

        Map<Long, String> titleOf = new HashMap<>();
        if (!chainIds.isEmpty()) {
            for (PageTreeItem item : pages.findTreeItemsByIds(chainIds)) titleOf.put(item.id(), item.title());
        }
        return allowed.stream()
                .map(id -> new com.platform.wikibackend.page.dto.PagePath(
                        id,
                        chains.get(id).stream().map(titleOf::get).filter(Objects::nonNull).toList()))
                .toList();
    }

    /** 제목 정확 일치 — `[[제목]]` 해석. 렌더러와 같은 기준(trim + 소문자, 같은 스페이스). */
    public List<PageNode> lookupByTitles(long userId, long spaceId, Collection<String> titles) {
        spaces.getForView(userId, spaceId);
        List<String> normalized = titles.stream()
                .filter(Objects::nonNull)
                .map(t -> t.trim().toLowerCase(Locale.ROOT))
                .filter(t -> !t.isEmpty())
                .distinct()
                .limit(LOOKUP_LIMIT)
                .toList();
        if (normalized.isEmpty()) return List.of();
        return withChildCounts(visible(userId, pages.findByTitles(spaceId, normalized)));
    }

    /**
     * id 묶음 조회 — 별표 목록처럼 "내가 이미 아는 id들의 현재 제목"이 필요한 곳이 쓴다.
     * 전량을 들고 있지 않으면 개명된 제목을 따라갈 방법이 없어 스냅샷이 조용히 낡는다.
     */
    public List<PageNode> byIds(long userId, long spaceId, Collection<Long> ids) {
        spaces.getForView(userId, spaceId);
        List<Long> wanted = ids.stream().filter(Objects::nonNull).distinct().limit(LOOKUP_LIMIT).toList();
        if (wanted.isEmpty()) return List.of();
        // 다른 스페이스의 id를 섞어 보내 남의 문서 제목을 캐내지 못하게 한 번에 걸러낸다
        // (id마다 findById를 도는 N+1을 만들지 않는다).
        Set<Long> inSpace = pages.findAllById(wanted).stream()
                .filter(p -> Objects.equals(p.getSpaceId(), spaceId))
                .map(Page::getId)
                .collect(java.util.stream.Collectors.toSet());
        if (inSpace.isEmpty()) return List.of();
        List<PageTreeItem> items = pages.findTreeItemsByIds(inSpace);
        return withChildCounts(visible(userId, items));
    }

    /** 제목 부분 일치 — 사이드바 필터와 `[[` 자동완성. */
    public List<PageNode> searchByTitle(long userId, long spaceId, String query) {
        spaces.getForView(userId, spaceId);
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return List.of();
        return withChildCounts(visible(userId, pages.searchByTitle(spaceId, q, Limit.of(SEARCH_LIMIT))));
    }

    /**
     * 최근 수정 문서 — 스페이스 개요가 쓴다.
     * 제한으로 걸러진 만큼 결과가 줄어드니 넉넉히 읽고 요청한 수만큼 자른다.
     */
    public List<PageNode> recentlyUpdated(long userId, long spaceId, int limit) {
        spaces.getForView(userId, spaceId);
        int capped = Math.min(Math.max(limit, 1), SEARCH_LIMIT);
        List<PageTreeItem> candidates =
                pages.findRecentlyUpdated(spaceId, Limit.of(Math.min(capped * 4, SEARCH_LIMIT * 4)));
        return withChildCounts(visible(userId, candidates)).stream().limit(capped).toList();
    }

    /** 후손 전체 — 내보내기(하위 포함)와 삭제 영향 표시가 쓴다. */
    public List<PageNode> descendants(long userId, long pageId) {
        Page root = pages.findById(pageId)
                .orElseThrow(() -> new NotFoundException("페이지 없음: " + pageId));
        spaces.require(userId, root.getSpaceId(), WikiAction.VIEW);
        effective.requireView(userId, root);
        List<Long> ids = pages.findDescendantIds(pageId);
        if (ids.isEmpty()) return List.of();
        return withChildCounts(visible(userId, pages.findTreeItemsByIds(ids)));
    }

    /* ── 내부 ────────────────────────────────────────────── */

    /**
     * 페이지 제한(W18) 필터. 트리 항목에는 spaceId가 없어 엔티티를 다시 읽는다 —
     * 후보 수가 화면 단위로 묶여 있어(자식 목록·검색 상한) 전량 스캔이 되지 않는다.
     */
    private List<PageTreeItem> visible(long userId, List<PageTreeItem> items) {
        if (items.isEmpty()) return List.of();
        List<Page> loaded = pages.findAllById(items.stream().map(PageTreeItem::id).toList());
        Set<Long> allowed = effective.viewablePageIds(userId, loaded);
        return items.stream().filter(it -> allowed.contains(it.id())).toList();
    }

    private List<PageNode> withChildCounts(List<PageTreeItem> items) {
        if (items.isEmpty()) return List.of();
        Map<Long, Long> counts = new HashMap<>();
        for (PageRepository.ParentCount row : pages.countChildren(items.stream().map(PageTreeItem::id).toList())) {
            counts.put(row.getParentId(), row.getCount());
        }
        return items.stream().map(it -> PageNode.of(it, counts.getOrDefault(it.id(), 0L))).toList();
    }
}
