package com.platform.wikibackend.permission;

import com.platform.common.error.ForbiddenException;
import com.platform.common.error.NotFoundException;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRestriction;
import com.platform.wikibackend.permission.dto.InheritedRestriction;
import com.platform.wikibackend.permission.dto.PageRestrictionsResponse;
import com.platform.wikibackend.permission.dto.RestrictionPrincipal;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRestrictionRepository;
import com.platform.wikibackend.space.SpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 페이지 제한 관리(자물쇠 다이얼로그) — W18 설계 §6.
 *
 * 접근 규칙:
 * - 조회: effective VIEW 통과자 또는 space ADMIN(제한 "관리"는 ADMIN 예외 — 본문과 달리
 *   제한 목록 자체는 본문을 싣지 않으므로 ADR 규칙 6과 충돌하지 않는다).
 * - 교체: effective EDIT 통과자 또는 space ADMIN. 전체 교체(부분 패치 없음 — 다이얼로그가
 *   전체 상태를 안다).
 * - 셀프 락아웃 가드: ADMIN이 아닌 요청자가 본인 USER 또는 소속 TEAM이 빠진 VIEW 제한을
 *   걸면 400 — 저장 직후 자기 문서를 못 보게 되는 실수를 막는다(ADMIN은 의도적 구성 허용).
 *
 * principal 이름 해석은 프론트가 org 디렉터리(REST)로 한다 — wiki는 id만 저장·반환
 * (작성자 표시와 같은 기존 패턴. 설계서의 "응답 시 이름 채움"은 wiki→org REST 신규 결합이라
 * 프론트 해석으로 조정, 계약 문서에 기록).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PageRestrictionService {

    private final PageRepository pages;
    private final PageRestrictionRepository restrictions;
    private final SpaceService spaces;
    private final EffectivePermissionService effective;
    private final PermissionClient permissions;
    private final PrincipalDirectory principalDirectory;
    private final com.platform.wikibackend.audit.AuditService audit;
    private final TeamDirectory teams;

    @Transactional(readOnly = true)
    public PageRestrictionsResponse get(long userId, long pageId) {
        Page page = requirePage(pageId);
        spaces.require(userId, page.getSpaceId(), WikiAction.VIEW);
        if (!isSpaceAdmin(userId, page.getSpaceId())) {
            effective.requireView(userId, page);
        }
        List<PageRestriction> own = restrictions.findByPageId(pageId);
        return new PageRestrictionsResponse(
                principals(own, PageRestriction.Type.VIEW),
                principals(own, PageRestriction.Type.EDIT),
                inheritedView(page));
    }

    /** 전체 교체 — view/edit 목록을 통째로 새 상태로 만든다. */
    public PageRestrictionsResponse replace(long userId, long pageId,
                                            List<RestrictionPrincipal> view,
                                            List<RestrictionPrincipal> edit) {
        Page page = requirePage(pageId);
        spaces.require(userId, page.getSpaceId(), WikiAction.VIEW);
        boolean admin = isSpaceAdmin(userId, page.getSpaceId());
        if (!admin) {
            effective.requireEdit(userId, page);
            // 셀프 락아웃 가드 — 새 VIEW 목록이 비어있지 않은데 자신(또는 소속 팀)이 없으면 거부
            if (!view.isEmpty() && view.stream().noneMatch(p -> matchesSelf(userId, p))) {
                throw new IllegalArgumentException("자신을 보기 제한 목록에서 뺄 수 없습니다");
            }
        }
        List<RestrictionPrincipal> requested = new ArrayList<>();
        requested.addAll(dedupe(view));
        requested.addAll(dedupe(edit));
        principalDirectory.requireExisting(requested);

        restrictions.deleteByPageId(pageId);
        List<PageRestriction> rows = new ArrayList<>();
        for (RestrictionPrincipal p : dedupe(view)) {
            rows.add(PageRestriction.of(pageId, PageRestriction.Type.VIEW, p.toType(), p.id(), userId));
        }
        for (RestrictionPrincipal p : dedupe(edit)) {
            rows.add(PageRestriction.of(pageId, PageRestriction.Type.EDIT, p.toType(), p.id(), userId));
        }
        restrictions.saveAll(rows);
        // 접근 범위가 바뀌는 조작이라 흔적을 남긴다 — "언제부터 이 페이지가 잠겼나"의 유일한 근거다.
        audit.recordPage(userId, com.platform.wikibackend.domain.AuditAction.PAGE_RESTRICTIONS_CHANGED,
                page, rows.isEmpty() ? "제한 해제" : "보기 " + dedupe(view).size() + "명/팀, 편집 " + dedupe(edit).size() + "명/팀");
        return new PageRestrictionsResponse(
                principals(rows, PageRestriction.Type.VIEW),
                principals(rows, PageRestriction.Type.EDIT),
                inheritedView(page));
    }

    /**
     * 이관 전용 내부 교체(W29 M2 §4.3).
     *
     * 공개 경로({@link #replace})와 세 가지가 다르고, 전부 의도한 것이다.
     * 1. **권한 검사를 하지 않는다** — 부르는 쪽은 잡 요청자(대상 스페이스 ADMIN)를 대신하는 워커이고,
     *    대상 문서는 방금 그 워커가 만든 것이다.
     * 2. **셀프 락아웃 가드를 걸지 않는다** — 원본의 제한을 그대로 옮기는 것이 목적이고, 요청자가
     *    목록에 없는 구성도 원본에 있었다면 그것이 정답이다(ADMIN이 공개 경로에서 갖는 재량과 같다).
     * 3. **감사 로그를 남기지 않는다** — 수백 건의 이관을 사람이 건 것처럼 기록하면 "언제부터 이
     *    페이지가 잠겼나"의 근거가 오히려 흐려진다. 이관의 기록은 migration_job이 들고 있다.
     *
     * 주체 실재 검증(principalDirectory)도 부르지 않는다 — 여기 오는 id는 org 대조를 이미 통과했거나
     * 잡 요청자 본인이다.
     */
    public void replaceImported(long pageId, List<RestrictionPrincipal> view,
                                List<RestrictionPrincipal> edit, long actorId) {
        restrictions.deleteByPageId(pageId);
        List<PageRestriction> rows = new ArrayList<>();
        for (RestrictionPrincipal p : dedupe(view)) {
            rows.add(PageRestriction.of(pageId, PageRestriction.Type.VIEW, p.toType(), p.id(), actorId));
        }
        for (RestrictionPrincipal p : dedupe(edit)) {
            rows.add(PageRestriction.of(pageId, PageRestriction.Type.EDIT, p.toType(), p.id(), actorId));
        }
        restrictions.saveAll(rows);
    }

    private boolean matchesSelf(long userId, RestrictionPrincipal p) {
        if (p.toType() == PageRestriction.PrincipalType.USER) return p.id() == userId;
        return teams.teamsOf(userId).contains(p.id());
    }

    private Set<RestrictionPrincipal> dedupe(List<RestrictionPrincipal> in) {
        return new LinkedHashSet<>(in == null ? List.of() : in);
    }

    private boolean isSpaceAdmin(long userId, long spaceId) {
        return permissions.isAllowed(userId, spaceId, WikiAction.ADMIN);
    }

    private Page requirePage(long pageId) {
        return pages.findById(pageId).orElseThrow(() -> new NotFoundException("페이지 없음: " + pageId));
    }

    private static List<RestrictionPrincipal> principals(List<PageRestriction> rows, PageRestriction.Type type) {
        return rows.stream()
                .filter(r -> r.getType() == type)
                .map(r -> new RestrictionPrincipal(r.getPrincipalType().name(), r.getPrincipalId()))
                .toList();
    }

    /** 조상의 VIEW 제한(읽기 전용 표시용) — 자기 자신은 제외, 루트 방향 순서. */
    private List<InheritedRestriction> inheritedView(Page page) {
        Map<Long, Long> parentOf = new HashMap<>();
        for (PageRepository.IdParent row : pages.findIdParentBySpaceId(page.getSpaceId())) {
            parentOf.put(row.getId(), row.getParentId());
        }
        Map<Long, List<PageRestriction>> viewBypage = new HashMap<>();
        for (PageRestriction r : restrictions.findBySpaceId(page.getSpaceId())) {
            if (r.getType() == PageRestriction.Type.VIEW) {
                viewBypage.computeIfAbsent(r.getPageId(), k -> new ArrayList<>()).add(r);
            }
        }
        List<InheritedRestriction> out = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        Long cursor = parentOf.get(page.getId());
        while (cursor != null && visited.add(cursor)) {
            List<PageRestriction> rows = viewBypage.get(cursor);
            if (rows != null) {
                String title = pages.findById(cursor).map(Page::getTitle).orElse("");
                out.add(new InheritedRestriction(cursor, title,
                        rows.stream()
                                .map(r -> new RestrictionPrincipal(r.getPrincipalType().name(), r.getPrincipalId()))
                                .toList()));
            }
            cursor = parentOf.get(cursor);
        }
        return out;
    }
}
