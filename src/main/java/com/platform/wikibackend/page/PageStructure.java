package com.platform.wikibackend.page;

import com.platform.common.error.NotFoundException;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageType;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.repository.PageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 페이지 트리 구조 공용 연산 — 존재 확인·행 잠금·서브트리 수집·형제 순번·부모 검증.
 *
 * 생성·이동·삭제·복원이 같은 규칙을 써야 한다(부모는 같은 스페이스, 자기 자손 아래 금지, 보관 부모 금지).
 * 서비스마다 복제하면 한쪽만 고쳐지는 일이 생기므로 여기 한 곳에 둔다. 트랜잭션은 호출부 것을 따른다.
 */
@Component
@RequiredArgsConstructor
public class PageStructure {

    private final PageRepository pages;
    private final EffectivePermissionService effective;

    /** 존재 검증만 — 권한은 호출부가. 첨부(Task 11)·리비전(Task 10)이 재사용. */
    public Page getOwned(long pageId) {
        return pages.findById(pageId).orElseThrow(() -> new NotFoundException("페이지 없음: " + pageId));
    }

    /** 버전을 올리는 쓰기용 — page 행을 잠근다. 공동 초안을 건드리는 경로는 모두 page를 먼저 잠근다. */
    public Page lockOwned(long pageId) {
        return pages.findByIdForUpdate(pageId).orElseThrow(() -> new NotFoundException("페이지 없음: " + pageId));
    }


    /** 자기 자신 포함 서브트리 전체. visited로 손상 데이터의 순환에도 무한 루프하지 않는다. */
    public List<Page> collectSubtree(long rootId) {
        // 스페이스 전체의 (id, parentId)를 한 번에 읽고 메모리에서 BFS — 이전에는 노드당
        // findByParentId를 날려 서브트리 크기만큼 왕복(N+1)했다(규모 검토 2026-08-23).
        // 서브트리는 항상 한 스페이스 안에 있다(이동도 서브트리 단위로 스페이스를 옮긴다).
        Page root = getOwned(rootId);
        Map<Long, List<Long>> childrenOf = new HashMap<>();
        for (PageRepository.IdParent row : pages.findIdParentBySpaceId(root.getSpaceId())) {
            childrenOf.computeIfAbsent(row.getParentId(), k -> new ArrayList<>()).add(row.getId());
        }
        // BFS로 id만 모은 뒤 한 번에 읽는다 — 노드마다 findById를 돌던 N+1을 없앴다(감사 W-15).
        List<Long> ids = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>(List.of(rootId));
        while (!queue.isEmpty()) {
            long id = queue.poll();
            if (!visited.add(id)) continue;
            ids.add(id);
            for (Long child : childrenOf.getOrDefault(id, List.of())) queue.add(child);
        }
        Map<Long, Page> byId = new HashMap<>();
        for (Page p : pages.findAllById(ids)) byId.put(p.getId(), p);
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList(); // BFS 순서 유지
    }


    /** 떠난 그룹을 1..n으로 조밀화 — 빈 번호를 남기지 않아야 이후 삽입 계산이 단순하다. */
    public void resequenceSiblings(Long spaceId, Long parentId, long excludedPageId) {
        List<Page> siblings = pages.findSiblings(spaceId, parentId).stream()
                .filter(p -> p.getId() != excludedPageId)
                .toList();
        for (int i = 0; i < siblings.size(); i++) siblings.get(i).resequence(i + 1L);
    }

    /** parent는 같은 스페이스 + (이동 시) 자기 자신·자손 금지. */
    public void validateParent(Long spaceId, Long parentId, Long movingPageId) {
        if (parentId == null) return;
        Page parent = pages.findById(parentId).orElseThrow(() -> new IllegalArgumentException("부모 페이지 없음: " + parentId));
        if (!Objects.equals(parent.getSpaceId(), spaceId)) {
            throw new IllegalArgumentException("부모는 같은 스페이스여야 합니다");
        }
        if (parent.getType() == PageType.BLOG) {
            throw new IllegalArgumentException("블로그 글 아래에는 문서를 둘 수 없습니다");
        }
        if (movingPageId != null) {
            // 새 부모의 조상 폐포(자신 포함)를 재귀 CTE 한 번으로 — 조상마다 findById를 돌지 않는다(감사 W-15).
            Map<Long, Long> parentOf = new HashMap<>();
            for (Object[] row : pages.findAncestorClosure(List.of(parentId))) {
                parentOf.put(((Number) row[0]).longValue(), row[1] == null ? null : ((Number) row[1]).longValue());
            }
            // visited: 손상 데이터(parent_id 순환)에서 무한 루프하지 않는다. 폐포는 깊이 64에서 끊기므로
            // 그보다 깊은 트리는 남은 구간만 한 칸씩 읽는다(정상 규모에서는 타지 않는 길).
            Set<Long> visited = new HashSet<>();
            Long cursor = parentId;
            while (cursor != null && visited.add(cursor)) {
                if (Objects.equals(cursor, movingPageId)) throw new IllegalArgumentException("자기 자신/자손 아래로 이동 불가");
                Long current = cursor;
                cursor = parentOf.containsKey(current)
                        ? parentOf.get(current)
                        : pages.findById(current).map(Page::getParentId).orElse(null);
            }
        }
    }

    /** 새 자식 생성·이동 대상은 같은 스페이스이며 요청자가 직접 수정할 수 있는 페이지여야 한다. */
    public void requireEditableTargetParent(long userId, Long spaceId, Long parentId) {
        if (parentId == null) return;
        Page parent = pages.findById(parentId)
                .orElseThrow(() -> new IllegalArgumentException("부모 페이지 없음: " + parentId));
        if (!Objects.equals(parent.getSpaceId(), spaceId)) {
            throw new IllegalArgumentException("부모는 같은 스페이스여야 합니다");
        }
        effective.requireEdit(userId, parent);
        PageWriteGuard.requireWritableParent(parent);
    }
}
