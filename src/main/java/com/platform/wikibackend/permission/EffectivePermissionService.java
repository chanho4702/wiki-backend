package com.platform.wikibackend.permission;

import com.platform.common.error.ForbiddenException;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRestriction;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRestrictionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Effective permission 단일 함수 (W18 설계서 §3, ADR-W14-06).
 *
 * 판정 순서 — space 권한(org-service)은 호출부(SpaceService.require)가 먼저 확인한다는 전제:
 * 1. VIEW: 루트→페이지 조상 체인의 각 노드에 VIEW 제한 행이 있으면, 사용자 또는 소속 팀이
 *    그 목록에 있어야 한다(교집합 상속 — 하나라도 실패면 거부).
 * 2. EDIT: VIEW 통과 + 현재 페이지의 EDIT 제한(있으면 포함 필요). 조상의 EDIT 제한은 보지
 *    않고, EDIT 제한이 VIEW를 암시하지도 않는다.
 * 3. space ADMIN도 본문 읽기는 1~2를 그대로 탄다 — 제한 "관리"만 예외(증분 2의 관리 API).
 *
 * 본문·트리·첨부·댓글·협업 티켓·알림이 전부 이 서비스 하나를 거쳐야 한다(설계 §3.2) —
 * 한 경로라도 우회하면 그 경로가 누출구가 된다.
 *
 * 로드 비용(2026-08-28 규모 개선): 단건·묶음 판정은 **대상의 조상 폐포 2쿼리**만 읽는다
 * (재귀 CTE + 그 id들의 제한 행). 예전에는 스페이스 전 페이지를 읽어 부모 맵을 만들었고,
 * 그래서 페이지 한 장 여는 비용이 스페이스 크기에 비례했다.
 * 스페이스 전량이 정말 필요한 곳은 트리 필터(visiblePageIds) 하나뿐이라 거기만 남겼다.
 *
 * 캐시를 쓰지 않는 것은 의도다 — 제한·트리 변경 직후의 스테일 인덱스는 그대로 누출이라,
 * 무효화가 완벽해야만 안전한 캐시보다 질의를 싸게 만드는 쪽을 택했다.
 */
@Service
@RequiredArgsConstructor
public class EffectivePermissionService {

    private final PageRepository pages;
    private final PageRestrictionRepository restrictions;
    private final TeamDirectory teams;

    public void requireView(long userId, Page page) {
        if (!canView(userId, page)) {
            throw new ForbiddenException("이 페이지를 볼 권한이 없습니다");
        }
    }

    /** 알림 등 사용자별 노출 필터가 예외 대신 판정값을 필요로 할 때 쓴다(space 권한은 호출부 책임). */
    public boolean canView(long userId, Page page) {
        return chainIndex(List.of(page.getId())).canView(principalsOf(userId), page.getId());
    }

    /** 여러 스페이스의 알림 목록처럼 페이지 묶음을 판정할 때 스페이스별 인덱스를 한 번만 만든다. */
    public Set<Long> viewablePageIds(long userId, Collection<Page> targets) {
        if (targets.isEmpty()) return Set.of();
        Principals me = principalsOf(userId);
        // 스페이스가 섞여 있어도 폐포 한 번이면 된다 — 체인은 스페이스를 넘지 않는다.
        SpaceIndex idx = chainIndex(targets.stream().map(Page::getId).toList());
        Set<Long> visible = new HashSet<>();
        for (Page page : targets) {
            if (idx.canView(me, page.getId())) visible.add(page.getId());
        }
        return visible;
    }

    public void requireEdit(long userId, Page page) {
        SpaceIndex idx = chainIndex(List.of(page.getId()));
        Principals me = principalsOf(userId);
        if (!idx.canView(me, page.getId())) {
            throw new ForbiddenException("이 페이지를 볼 권한이 없습니다");
        }
        if (!idx.canEdit(me, page.getId())) {
            throw new ForbiddenException("이 페이지를 수정할 권한이 없습니다");
        }
    }

    /**
     * 서브트리 구조 변경 전 전수 판정. 인덱스는 스페이스별 1회, 팀 목록은 사용자별 1회만
     * 조회해 페이지 수만큼 전체 스페이스 쿼리를 반복하지 않는다.
     */
    public void requireEditAll(long userId, Collection<Page> targets) {
        if (targets.isEmpty()) return;
        Principals me = principalsOf(userId);
        SpaceIndex idx = chainIndex(targets.stream().map(Page::getId).toList());
        for (Page page : targets) {
            if (!idx.canView(me, page.getId())) {
                throw new ForbiddenException("이 페이지를 볼 권한이 없습니다");
            }
            if (!idx.canEdit(me, page.getId())) {
                throw new ForbiddenException("이 페이지를 수정할 권한이 없습니다");
            }
        }
    }

    /**
     * 트리 필터용 — 이 스페이스에서 사용자가 볼 수 있는 페이지 id 집합.
     * 제한이 전무하면 null(전부 허용 — 호출부가 필터를 건너뛰는 최적화 힌트).
     */
    public Set<Long> visiblePageIds(long userId, long spaceId) {
        SpaceIndex idx = index(spaceId);
        if (!idx.hasViewRestrictions()) return null;
        Principals me = principalsOf(userId);
        Set<Long> visible = new HashSet<>();
        for (Long pageId : idx.pageIds()) {
            if (idx.canView(me, pageId)) visible.add(pageId);
        }
        return visible;
    }

    /**
     * 이동 영향(설계 §5) — 새 조상 체인에서 이 페이지에 "새로" 적용될 VIEW 제한 노드들.
     * 페이지 자신·서브트리의 제한은 함께 이동하므로 비교 대상이 아니다. 비어 있지 않으면
     * 이동 확정 전에 사용자 확인이 필요하다(MoveImpactException 경로). 팀 중첩과 space grant를
     * 모두 펼치지 않고 보수적으로 경고하므로 이 목록을 정확한 접근 상실자 목록으로 부르지 않는다.
     */
    public java.util.List<com.platform.wikibackend.permission.dto.InheritedRestriction> newViewRestrictionsAfterMove(
            Page page, Long targetParentId) {
        // 현재 체인과 옮겨갈 자리의 체인 둘만 있으면 된다 — 두 스페이스 전량을 읽지 않는다.
        // 폐포 질의는 id 기반이라 스페이스를 넘는 이동도 같은 한 번으로 덮인다.
        SpaceIndex chains = chainIndex(java.util.Arrays.asList(page.getId(), targetParentId));

        Set<Long> currentRestricted = new HashSet<>();
        walkViewRestricted(chains, chains.parentOf().get(page.getId()), currentRestricted::add);

        java.util.List<com.platform.wikibackend.permission.dto.InheritedRestriction> out = new ArrayList<>();
        walkViewRestricted(chains, targetParentId, nodeId -> {
            if (currentRestricted.contains(nodeId)) return;
            String title = pages.findById(nodeId).map(Page::getTitle).orElse("");
            out.add(new com.platform.wikibackend.permission.dto.InheritedRestriction(nodeId, title,
                    chains.view().get(nodeId).stream()
                            .map(r -> new com.platform.wikibackend.permission.dto.RestrictionPrincipal(
                                    r.getPrincipalType().name(), r.getPrincipalId()))
                            .toList()));
        });
        return out;
    }

    /** cursor부터 루트까지 올라가며 VIEW 제한이 걸린 노드 id를 방문한다(순환 방어). */
    private static void walkViewRestricted(SpaceIndex idx, Long cursor, java.util.function.Consumer<Long> visit) {
        Set<Long> visited = new HashSet<>();
        while (cursor != null && visited.add(cursor)) {
            if (idx.view().containsKey(cursor)) visit.accept(cursor);
            cursor = idx.parentOf().get(cursor);
        }
    }

    private Principals principalsOf(long userId) {
        return new Principals(userId, teams);
    }

    /**
     * 대상 페이지들의 조상 폐포만 담은 인덱스. 판정에 필요한 것은 조상 체인과 그 위의 제한뿐이라
     * 스페이스 전량을 읽지 않는다.
     */
    private SpaceIndex chainIndex(Collection<Long> pageIds) {
        List<Long> seeds = pageIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (seeds.isEmpty()) return new SpaceIndex(Map.of(), Map.of(), Map.of());
        Map<Long, Long> parentOf = new HashMap<>();
        for (Object[] row : pages.findAncestorClosure(seeds)) {
            parentOf.put(((Number) row[0]).longValue(),
                    row[1] == null ? null : ((Number) row[1]).longValue());
        }
        return new SpaceIndex(parentOf, byPage(restrictions.findByPageIdIn(parentOf.keySet()),
                PageRestriction.Type.VIEW), byPage(restrictions.findByPageIdIn(parentOf.keySet()),
                PageRestriction.Type.EDIT));
    }

    private static Map<Long, List<PageRestriction>> byPage(List<PageRestriction> rows,
                                                          PageRestriction.Type type) {
        Map<Long, List<PageRestriction>> out = new HashMap<>();
        for (PageRestriction r : rows) {
            if (r.getType() != type) continue;
            out.computeIfAbsent(r.getPageId(), k -> new ArrayList<>()).add(r);
        }
        return out;
    }

    /** 스페이스 전량 인덱스 — 트리 필터(visiblePageIds)처럼 정말 전부가 필요한 곳만 쓴다. */
    private SpaceIndex index(long spaceId) {
        Map<Long, Long> parentOf = new HashMap<>();
        for (PageRepository.IdParent row : pages.findIdParentAnyBySpaceId(spaceId)) {
            parentOf.put(row.getId(), row.getParentId());
        }
        Map<Long, List<PageRestriction>> view = new HashMap<>();
        Map<Long, List<PageRestriction>> edit = new HashMap<>();
        for (PageRestriction r : restrictions.findBySpaceId(spaceId)) {
            (r.getType() == PageRestriction.Type.VIEW ? view : edit)
                    .computeIfAbsent(r.getPageId(), k -> new ArrayList<>()).add(r);
        }
        return new SpaceIndex(parentOf, view, edit);
    }

    /** 팀 멤버십은 TEAM 제한을 실제로 판정할 때 1회만 조회(memo) — 제한 없는 요청은 org 왕복이 없다. */
    private static final class Principals {
        private final long userId;
        private final TeamDirectory teams;
        private Set<Long> teamIds;

        Principals(long userId, TeamDirectory teams) {
            this.userId = userId;
            this.teams = teams;
        }

        boolean matches(PageRestriction r) {
            if (r.getPrincipalType() == PageRestriction.PrincipalType.USER) {
                return r.getPrincipalId() == userId;
            }
            if (teamIds == null) teamIds = Set.copyOf(teams.teamsOf(userId));
            return teamIds.contains(r.getPrincipalId());
        }
    }

    private record SpaceIndex(
            Map<Long, Long> parentOf,
            Map<Long, List<PageRestriction>> view,
            Map<Long, List<PageRestriction>> edit) {

        boolean hasViewRestrictions() {
            return !view.isEmpty();
        }

        Set<Long> pageIds() {
            return parentOf.keySet();
        }

        boolean canView(Principals me, long pageId) {
            // 루트까지 조상 체인 전체 검사 — visited는 손상 데이터(parent 순환) 무한 루프 방지
            Set<Long> visited = new HashSet<>();
            Long cursor = pageId;
            while (cursor != null && visited.add(cursor)) {
                List<PageRestriction> rows = view.get(cursor);
                if (rows != null && rows.stream().noneMatch(me::matches)) return false;
                cursor = parentOf.get(cursor);
            }
            return true;
        }

        boolean canEdit(Principals me, long pageId) {
            List<PageRestriction> rows = edit.get(pageId);
            return rows == null || rows.stream().anyMatch(me::matches);
        }
    }
}
