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
 * 4. 초안(Page.status=DRAFT, 2026-10-18 사용자 결정): 작성자 + 스페이스 ADMIN + 전역 ADMIN만 본다.
 *    VIEW 제한과 같은 자리(조상 체인)에서 판정하므로 초안 아래의 하위 문서도 함께 가려진다 —
 *    트리에서 부모가 사라진 고아가 생기지 않고, 위치·제목이 새지 않는다. 게시하면 정상 노출된다.
 *    관리자 판정은 남의 초안을 실제로 만났을 때만 org에 묻는다(사용자·스페이스별 1회, memo).
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
    private final PermissionClient permissions;

    public void requireView(long userId, Page page) {
        if (!canView(userId, page)) {
            throw new ForbiddenException("이 페이지를 볼 권한이 없습니다");
        }
    }

    /** 알림 등 사용자별 노출 필터가 예외 대신 판정값을 필요로 할 때 쓴다(space 권한은 호출부 책임). */
    public boolean canView(long userId, Page page) {
        return chainIndex(List.of(page.getId())).canView(principalsOf(userId), page.getId());
    }

    /**
     * 한 페이지를 여러 사용자에 대해 판정할 때 — 조상 체인 인덱스는 한 번만 만들고 사용자별로는
     * 주체(팀·관리자 memo)만 바꾼다. 알림 발송이 수신자마다 질의 2회를 돌던 비용을 없앤다(감사 W-11).
     * 사용자별 판정에서 org 불능이면 그 호출에서 503 예외가 난다 — 호출부가 사용자 단위로 처리한다.
     */
    public java.util.function.LongPredicate viewJudge(long pageId) {
        SpaceIndex idx = chainIndex(List.of(pageId));
        return userId -> idx.canView(principalsOf(userId), pageId);
    }

    /** 여러 스페이스의 알림 목록처럼 페이지 묶음을 판정할 때 스페이스별 인덱스를 한 번만 만든다. */
    public Set<Long> viewablePageIds(long userId, Collection<Page> targets) {
        return viewableIds(userId, targets.stream().map(Page::getId).toList());
    }

    /**
     * id만으로 묶음 판정 — 판정에 필요한 것은 id와 조상 체인뿐이다. 트리 항목·검색 후보처럼
     * 이미 가벼운 행을 들고 있는 호출부가 판정 때문에 본문(content)까지 든 엔티티를 다시 읽지 않게 한다.
     */
    public Set<Long> viewableIds(long userId, Collection<Long> pageIds) {
        if (pageIds.isEmpty()) return Set.of();
        Principals me = principalsOf(userId);
        // 스페이스가 섞여 있어도 폐포 한 번이면 된다 — 체인은 스페이스를 넘지 않는다.
        SpaceIndex idx = chainIndex(pageIds);
        Set<Long> visible = new HashSet<>();
        for (Long id : pageIds) {
            if (id != null && idx.canView(me, id)) visible.add(id);
        }
        return visible;
    }

    /**
     * 여러 스페이스에서 이 사용자가 **볼 수 없는** 페이지 id(휴지통 포함 인덱스 기준).
     *
     * 라이트 검색이 쓴다: 후보를 상한까지 잘라 놓고 뒤에서 거르면, 앞쪽 후보가 전부 제한 문서일 때
     * 그 뒤의 볼 수 있는 문서가 통째로 사라진다(2026-10-16 결함). 숨길 집합을 먼저 알면 SQL이
     * 처음부터 제외하고 세므로 정렬·페이지 경계·건수가 권한 적용 뒤의 값이 된다.
     *
     * VIEW 제한이 하나도 없는 스페이스는 인덱스를 만들지 않는다(질의 1회로 골라낸다) — 제한 없는
     * 설치에서 검색 비용이 늘지 않게. 판정은 {@link SpaceIndex#canView}를 그대로 쓴다: 규칙을
     * "제한 노드의 후손" 같은 두 번째 표현으로 옮겨 적으면 둘이 언젠가 갈린다. 캐시하지 않는다.
     */
    public Set<Long> hiddenPageIds(long userId, Collection<Long> spaceIds) {
        if (spaceIds.isEmpty()) return Set.of();
        // 판정이 필요한 스페이스: 자체 VIEW 제한이 있거나, 조상 체인이 다른 스페이스로 넘어가는 페이지가
        // 있는 곳(부모가 다른 스페이스로 옮겨진 뒤 휴지통에서 되살린 하위 등 — 그 조상의 제한도 상속된다).
        Set<Long> targets = new java.util.LinkedHashSet<>(restrictions.findSpaceIdsWithViewRestrictions(spaceIds));
        targets.addAll(pages.findSpaceIdsWithForeignParents(spaceIds));
        targets.addAll(pages.findSpaceIdsWithOthersDrafts(spaceIds, userId)); // 남의 초안이 있는 곳(초안 정책)
        if (targets.isEmpty()) return Set.of();
        Principals me = principalsOf(userId);
        Set<Long> hidden = new HashSet<>();
        for (Long spaceId : targets) {
            SpaceIndex idx = index(spaceId);
            // 스페이스 밖 조상: 그 조상부터 루트까지는 체인 폐포로 따로 판정한다(스페이스 인덱스에 없다).
            Map<Long, Long> exitOf = new HashMap<>();
            for (Long pageId : idx.pageIds()) {
                Long exit = idx.exitOf(pageId);
                if (exit != null) exitOf.put(pageId, exit);
            }
            Set<Long> blockedExits = new HashSet<>();
            if (!exitOf.isEmpty()) {
                Set<Long> exits = new HashSet<>(exitOf.values());
                SpaceIndex outside = chainIndex(exits);
                for (Long exit : exits) {
                    if (!outside.canView(me, exit)) blockedExits.add(exit);
                }
            }
            for (Long pageId : idx.pageIds()) {
                Long exit = exitOf.get(pageId);
                if (!idx.canView(me, pageId) || (exit != null && blockedExits.contains(exit))) hidden.add(pageId);
            }
        }
        return hidden;
    }

    /** 예외 대신 판정값 — 화면의 편집 버튼 표시용(space 권한은 호출부 책임). */
    public boolean canEdit(long userId, Page page) {
        SpaceIndex idx = chainIndex(List.of(page.getId()));
        Principals me = principalsOf(userId);
        return idx.canView(me, page.getId()) && idx.canEdit(me, page.getId());
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
        if (!idx.hasViewRestrictions() && idx.draftsAllBy(userId)) return null;
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
        return new Principals(userId, teams, permissions);
    }

    /**
     * 대상 페이지들의 조상 폐포만 담은 인덱스. 판정에 필요한 것은 조상 체인과 그 위의 제한뿐이라
     * 스페이스 전량을 읽지 않는다.
     */
    private SpaceIndex chainIndex(Collection<Long> pageIds) {
        List<Long> seeds = pageIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (seeds.isEmpty()) return new SpaceIndex(Map.of(), Map.of(), Map.of(), Map.of());
        Map<Long, Long> parentOf = new HashMap<>();
        for (Object[] row : pages.findAncestorClosure(seeds)) {
            parentOf.put(((Number) row[0]).longValue(),
                    row[1] == null ? null : ((Number) row[1]).longValue());
        }
        // 한 번 읽어 VIEW/EDIT로 나눈다 — 같은 행을 종류별로 두 번 조회할 이유가 없다.
        List<PageRestriction> rows = restrictions.findByPageIdIn(parentOf.keySet());
        return new SpaceIndex(parentOf, byPage(rows, PageRestriction.Type.VIEW),
                byPage(rows, PageRestriction.Type.EDIT), drafts(pages.findDraftOwners(parentOf.keySet())));
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
        return new SpaceIndex(parentOf, view, edit, drafts(pages.findDraftOwnersBySpaceId(spaceId)));
    }

    private static Map<Long, DraftOwner> drafts(List<Object[]> rows) {
        if (rows.isEmpty()) return Map.of();
        Map<Long, DraftOwner> out = new HashMap<>();
        for (Object[] row : rows) {
            out.put(((Number) row[0]).longValue(),
                    new DraftOwner(((Number) row[1]).longValue(), ((Number) row[2]).longValue()));
        }
        return out;
    }

    /** 초안 한 장의 작성자와 스페이스 — 판정에 필요한 전부. */
    private record DraftOwner(long createdBy, long spaceId) {
    }

    /** 팀 멤버십은 TEAM 제한을 실제로 판정할 때 1회만 조회(memo) — 제한 없는 요청은 org 왕복이 없다. */
    private static final class Principals {
        private final long userId;
        private final TeamDirectory teams;
        private final PermissionClient permissions;
        private Set<Long> teamIds;
        private final Map<Long, Boolean> spaceAdmin = new HashMap<>();
        private Boolean globalAdmin;

        Principals(long userId, TeamDirectory teams, PermissionClient permissions) {
            this.userId = userId;
            this.teams = teams;
            this.permissions = permissions;
        }

        /**
         * 초안을 볼 수 있는가 — 작성자 또는 그 스페이스 ADMIN 또는 전역 ADMIN. org 판정은 남의 초안을
         * 만났을 때만, 사용자·스페이스별로 한 번. org 불능이면 503이 그대로 올라간다(조용히 숨기지 않는다).
         */
        boolean readsDraft(DraftOwner draft) {
            if (draft.createdBy() == userId) return true;
            if (spaceAdmin.computeIfAbsent(draft.spaceId(),
                    sid -> permissions.isAllowed(userId, sid, WikiAction.ADMIN))) return true;
            if (globalAdmin == null) globalAdmin = permissions.checkGlobal(userId, WikiAction.ADMIN).allowed();
            return globalAdmin;
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
            Map<Long, List<PageRestriction>> edit,
            Map<Long, DraftOwner> drafts) {

        boolean hasViewRestrictions() {
            return !view.isEmpty();
        }

        /** 이 인덱스의 초안이 전부 이 사용자의 것인가(없으면 참) — 트리 필터 생략 힌트. */
        boolean draftsAllBy(long userId) {
            return drafts.values().stream().allMatch(d -> d.createdBy() == userId);
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
                DraftOwner draft = drafts.get(cursor);
                if (draft != null && !me.readsDraft(draft)) return false; // 초안 정책 — 하위도 함께 가려진다
                cursor = parentOf.get(cursor);
            }
            return true;
        }

        /**
         * 이 인덱스(한 스페이스) 밖으로 나가는 첫 조상 id — 체인이 스페이스 안에서 끝나면 null.
         * 스페이스 전량 인덱스는 그 스페이스의 행만 키로 가지므로, 키에 없는 부모가 곧 밖이다.
         */
        Long exitOf(long pageId) {
            Set<Long> visited = new HashSet<>();
            Long cursor = parentOf.get(pageId);
            visited.add(pageId);
            while (cursor != null && visited.add(cursor)) {
                if (!parentOf.containsKey(cursor)) return cursor;
                cursor = parentOf.get(cursor);
            }
            return null;
        }

        boolean canEdit(Principals me, long pageId) {
            List<PageRestriction> rows = edit.get(pageId);
            return rows == null || rows.stream().anyMatch(me::matches);
        }
    }
}
