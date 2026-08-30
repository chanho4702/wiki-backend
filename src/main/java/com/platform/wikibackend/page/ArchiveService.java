package com.platform.wikibackend.page;

import com.platform.wikibackend.audit.AuditService;
import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import com.platform.wikibackend.domain.AuditAction;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageType;
import com.platform.wikibackend.event.EventRelay;
import com.platform.wikibackend.event.WikiEvents;
import com.platform.wikibackend.page.dto.PageResponse;
import com.platform.wikibackend.page.dto.TrashItem;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.space.SpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 페이지 보관(W23) — 휴지통은 "지웠다", 보관은 "끝났지만 남겨 둔다".
 *
 * 지난 분기 회고·폐기된 설계처럼 더는 트리에 있을 이유가 없지만 링크로는 계속 읽혀야 하는
 * 문서가 트리를 채우고 있었다. 보관하면 트리·검색·자동완성에서 빠지고, URL로는 그대로 열린다.
 *
 * 묶음 규칙은 휴지통(TrashService)과 같다: 루트 표시 + 하위 cascade, 따로 보관한 하위 묶음은
 * 경계다. 목록 행도 같은 모양(TrashRow)이라 읽는 코드를 공유한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ArchiveService {

    private final PageRepository pages;
    private final SpaceService spaces;
    private final EffectivePermissionService effective;
    private final EventRelay events;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<TrashItem> list(long userId, long spaceId) {
        spaces.getForView(userId, spaceId);
        List<TrashRow> rows = archivedRows(spaceId);
        if (rows.isEmpty()) return List.of();
        Map<Long, List<TrashRow>> childrenOf = childrenIndex(rows);
        Set<Long> visible = effective.viewablePageIds(userId,
                pages.findAllById(rows.stream().map(TrashRow::id).toList()));
        return rows.stream()
                .filter(TrashRow::deletedRoot)
                .filter(row -> visible.contains(row.id()))
                .sorted(Comparator.comparing(TrashRow::deletedAt).reversed())
                .map(row -> new TrashItem(row.id(), row.title(), PageType.from(row.type()), row.icon(),
                        row.deletedAt(), row.deletedBy(), countDescendants(row.id(), childrenOf)))
                .toList();
    }

    /**
     * 보관 — 루트와 살아 있는 자손 전부. 자손 중 이미 보관된 묶음은 그대로 둔다(그 묶음의 루트가
     * 따로 있다). 편집 권한이 기준이다: 트리에서 문서를 치우는 것은 편집과 같은 무게다.
     */
    public PageResponse archive(long userId, long pageId) {
        Page root = pages.findById(pageId)
                .orElseThrow(() -> new NotFoundException("페이지 없음: " + pageId));
        if (root.isArchived()) throw new ConflictException("이미 보관된 문서입니다");
        spaces.require(userId, root.getSpaceId(), WikiAction.EDIT);

        List<Long> descendantIds = pages.findDescendantIds(root.getId());
        List<Page> batch = new ArrayList<>(List.of(root));
        if (!descendantIds.isEmpty()) {
            for (Page p : pages.findAllById(descendantIds)) {
                if (!p.isArchived()) batch.add(p);
            }
        }
        effective.requireEditAll(userId, batch);

        for (Page p : batch) p.archive(userId, p.getId().equals(root.getId()));
        pages.saveAll(batch);
        // 검색 색인에서 뺀다 — 보관된 문서는 검색으로 발견되면 안 된다(트리에서 뺀 이유와 같다).
        batch.forEach(p -> events.afterCommit(WikiEvents.pageDeleted(userId, p.getId(), p.getSpaceId())));
        audit.recordPage(userId, AuditAction.PAGE_ARCHIVED, root,
                batch.size() > 1 ? "하위 " + (batch.size() - 1) + "개 함께" : null);
        return PageResponse.from(root);
    }

    /** 보관 해제 — 루트와 함께 보관된 자손. 따로 보관한 하위 묶음은 그대로 남는다. */
    public PageResponse unarchive(long userId, long pageId) {
        Page root = pages.findById(pageId)
                .orElseThrow(() -> new NotFoundException("페이지 없음: " + pageId));
        if (!root.isArchived()) throw new ConflictException("보관되지 않은 문서입니다");
        spaces.require(userId, root.getSpaceId(), WikiAction.EDIT);
        // 부모가 보관 중이면 트리에 나타날 자리가 없다 — 부모부터 풀어야 한다.
        if (root.getParentId() != null
                && pages.findById(root.getParentId()).map(Page::isArchived).orElse(false)) {
            throw new ConflictException("상위 문서가 보관 중입니다. 상위 문서의 보관을 먼저 해제하세요");
        }

        List<Page> batch = batchOf(root);
        effective.requireEditAll(userId, batch);
        for (Page p : batch) p.unarchive();
        pages.saveAll(batch);
        batch.forEach(p -> events.afterCommit(WikiEvents.pageUpdated(userId, p)));
        audit.recordPage(userId, AuditAction.PAGE_UNARCHIVED, root,
                batch.size() > 1 ? "하위 " + (batch.size() - 1) + "개 함께" : null);
        return PageResponse.from(root);
    }

    /* ── 내부 ────────────────────────────────────────────── */

    private List<TrashRow> archivedRows(long spaceId) {
        return pages.findArchivedRows(spaceId).stream().map(TrashRow::from).toList();
    }

    private static Map<Long, List<TrashRow>> childrenIndex(List<TrashRow> rows) {
        Map<Long, List<TrashRow>> childrenOf = new HashMap<>();
        for (TrashRow row : rows) childrenOf.computeIfAbsent(row.parentId(), k -> new ArrayList<>()).add(row);
        return childrenOf;
    }

    /** 루트 + 루트가 아닌 보관 자손. 자손 중 archivedRoot=true는 별도 묶음이라 경계다. */
    private List<Page> batchOf(Page root) {
        Map<Long, List<TrashRow>> childrenOf = childrenIndex(archivedRows(root.getSpaceId()));
        List<Long> ids = new ArrayList<>();
        Set<Long> visited = new HashSet<>(Set.of(root.getId()));
        Deque<Long> queue = new ArrayDeque<>(List.of(root.getId()));
        while (!queue.isEmpty()) {
            for (TrashRow child : childrenOf.getOrDefault(queue.poll(), List.of())) {
                if (child.deletedRoot() || !visited.add(child.id())) continue;
                ids.add(child.id());
                queue.add(child.id());
            }
        }
        List<Page> batch = new ArrayList<>(List.of(root));
        if (!ids.isEmpty()) batch.addAll(pages.findAllById(ids));
        return batch;
    }

    private static int countDescendants(Long rootId, Map<Long, List<TrashRow>> childrenOf) {
        int count = 0;
        Set<Long> visited = new HashSet<>(Set.of(rootId));
        Deque<Long> queue = new ArrayDeque<>(List.of(rootId));
        while (!queue.isEmpty()) {
            for (TrashRow child : childrenOf.getOrDefault(queue.poll(), List.of())) {
                if (child.deletedRoot() || !visited.add(child.id())) continue;
                count++;
                queue.add(child.id());
            }
        }
        return count;
    }
}
