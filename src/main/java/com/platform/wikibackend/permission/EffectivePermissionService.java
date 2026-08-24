package com.platform.wikibackend.permission;

import com.platform.wikibackend.common.ForbiddenException;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRestriction;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRestrictionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
 * 로드는 스페이스 스코프 2쿼리((id,parent) + 제한 행)로 요청당 1회 — 규모 검토와 정합.
 * Caffeine 캐시는 제한 쓰기 API(증분 2)의 무효화 훅과 함께 붙인다(쓰기 경로 없이 캐시만
 * 넣으면 테스트·마이그레이션 직후 스테일만 남는다).
 */
@Service
@RequiredArgsConstructor
public class EffectivePermissionService {

    private final PageRepository pages;
    private final PageRestrictionRepository restrictions;
    private final TeamDirectory teams;

    public void requireView(long userId, Page page) {
        if (!index(page.getSpaceId()).canView(principalsOf(userId), page.getId())) {
            throw new ForbiddenException("이 페이지를 볼 권한이 없습니다");
        }
    }

    public void requireEdit(long userId, Page page) {
        SpaceIndex idx = index(page.getSpaceId());
        Principals me = principalsOf(userId);
        if (!idx.canView(me, page.getId())) {
            throw new ForbiddenException("이 페이지를 볼 권한이 없습니다");
        }
        if (!idx.canEdit(me, page.getId())) {
            throw new ForbiddenException("이 페이지를 수정할 권한이 없습니다");
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
     * 이동 확정 전에 사용자 확인이 필요하다(MoveImpactException 경로).
     */
    public java.util.List<com.platform.wikibackend.permission.dto.InheritedRestriction> newViewRestrictionsAfterMove(
            Page page, long targetSpaceId, Long targetParentId) {
        SpaceIndex source = index(page.getSpaceId());
        SpaceIndex target = targetSpaceId == page.getSpaceId() ? source : index(targetSpaceId);

        Set<Long> currentRestricted = new HashSet<>();
        walkViewRestricted(source, source.parentOf().get(page.getId()), currentRestricted::add);

        java.util.List<com.platform.wikibackend.permission.dto.InheritedRestriction> out = new ArrayList<>();
        walkViewRestricted(target, targetParentId, nodeId -> {
            if (currentRestricted.contains(nodeId)) return;
            String title = pages.findById(nodeId).map(Page::getTitle).orElse("");
            out.add(new com.platform.wikibackend.permission.dto.InheritedRestriction(nodeId, title,
                    target.view().get(nodeId).stream()
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

    private SpaceIndex index(long spaceId) {
        Map<Long, Long> parentOf = new HashMap<>();
        for (PageRepository.IdParent row : pages.findIdParentBySpaceId(spaceId)) {
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
