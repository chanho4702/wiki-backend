package com.platform.wikibackend.page;

import com.platform.common.error.NotFoundException;
import com.platform.wikibackend.domain.Attachment;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageType;
import com.platform.wikibackend.attachment.AttachmentStorageRouter;
import com.platform.wikibackend.event.EventRelay;
import com.platform.wikibackend.event.WikiEvents;
import com.platform.wikibackend.page.dto.PageResponse;
import com.platform.wikibackend.page.dto.PageRestoreResponse;
import com.platform.wikibackend.page.dto.TrashItem;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.repository.PageCommentRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageLabelRepository;
import com.platform.wikibackend.repository.PageWatchRepository;
import com.platform.wikibackend.repository.PageLinkRepository;
import com.platform.wikibackend.repository.PageRestrictionRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.space.SpaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * 휴지통(W21-1) — 소프트 삭제된 페이지의 목록·복원·영구 삭제.
 *
 * 왜 별도 서비스인가: 여기서만 Page의 @SQLRestriction("deleted_at is null")을 우회한다.
 * 우회 경로를 한 클래스로 몰아두면 "버린 문서가 어디서 되살아날 수 있는가"를 한 곳에서 감사할 수 있다.
 *
 * 권한 정책:
 * - 목록: 스페이스 VIEW + 페이지 제한(effective VIEW)로 행 단위 필터.
 * - 복원: 스페이스 EDIT + 복원되는 모든 페이지에 effective EDIT.
 * - 영구 삭제·비우기: 스페이스 ADMIN + 같은 effective EDIT 전수 검사.
 *   삭제 권한과 영구 삭제 권한을 분리하는 것이 휴지통의 요점이다.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class TrashService {

    private final PageRepository pages;
    private final AttachmentRepository attachments;
    private final PageRevisionRepository revisions;
    private final PageCommentRepository comments;
    private final PageRestrictionRepository restrictions;
    private final PageLabelRepository labels;
    private final PageLinkRepository links;
    private final PageWatchRepository watches;
    private final AttachmentStorageRouter storage;
    private final SpaceService spaces;
    private final com.platform.wikibackend.audit.AuditService audit;
    private final com.platform.wikibackend.repository.ReactionRepository reactionRepository;
    private final com.platform.wikibackend.repository.PageTaskRepository taskRepository;
    private final EffectivePermissionService effective;
    private final EventRelay events;

    @Transactional(readOnly = true)
    public List<TrashItem> list(long userId, long spaceId) {
        spaces.getForView(userId, spaceId);
        List<TrashRow> rows = trashRows(spaceId);
        if (rows.isEmpty()) return List.of();

        Map<Long, List<TrashRow>> childrenOf = new HashMap<>();
        for (TrashRow row : rows) {
            childrenOf.computeIfAbsent(row.parentId(), k -> new ArrayList<>()).add(row);
        }
        Set<Long> visible = effective.viewablePageIds(userId, loadForPermission(rows));
        return rows.stream()
                .filter(TrashRow::deletedRoot)
                .filter(row -> visible.contains(row.id()))
                .sorted(Comparator.comparing(TrashRow::deletedAt).reversed())
                .map(row -> new TrashItem(row.id(), row.title(),
                        PageType.from(row.type()), row.icon(),
                        row.deletedAt(), row.deletedBy(),
                        countDescendants(row.id(), childrenOf)))
                .toList();
    }

    /**
     * 복원 — 루트와 함께 버려진 자손을 되살린다. 따로 버려진 하위 묶음(deletedRoot=true)은
     * 그대로 휴지통에 남는다: 사용자가 두 번에 걸쳐 버린 것을 한 번의 복원으로 합치지 않는다.
     */
    public PageRestoreResponse restore(long userId, long pageId) {
        Page root = trashed(pageId);
        spaces.require(userId, root.getSpaceId(), WikiAction.EDIT);

        List<Page> batch = restorableBatch(root);
        effective.requireEditAll(userId, batch);

        // 원래 부모가 사라졌거나(영구 삭제) 아직 휴지통에 있으면 루트로 올린다 —
        // 없는 부모를 그대로 두면 트리 어디에도 나타나지 않는 고아가 된다.
        Long parentId = root.getParentId();
        boolean reparented = parentId != null && pages.findById(parentId).isEmpty();
        Long targetParent = reparented ? null : parentId;
        long order = pages.findMaxSortOrder(root.getSpaceId(), targetParent) + 1;

        root.restoreFromTrash(targetParent, order);
        for (Page descendant : batch) {
            if (descendant.getId().equals(root.getId())) continue;
            // 자손은 부모가 함께 살아나므로 원래 자리 그대로 — 순번도 버린 시점 값을 유지한다.
            descendant.restoreFromTrash(descendant.getParentId(), descendant.getSortOrder());
        }
        pages.saveAll(batch);
        // 색인은 삭제 시 pageDeleted로 비워졌다 — 복원한 전부를 다시 올린다.
        batch.forEach(p -> events.afterCommit(WikiEvents.pageUpdated(userId, p)));
        audit.recordPage(userId, com.platform.wikibackend.domain.AuditAction.PAGE_RESTORED, root,
                batch.size() > 1 ? "하위 " + (batch.size() - 1) + "개 함께" : null);
        return new PageRestoreResponse(PageResponse.from(root), reparented, batch.size());
    }

    /** 영구 삭제 — 루트와 함께 버려진 자손을 첨부 객체까지 되돌릴 수 없게 지운다. */
    public void purge(long userId, long pageId) {
        Page root = trashed(pageId);
        spaces.require(userId, root.getSpaceId(), WikiAction.ADMIN);
        List<Page> batch = restorableBatch(root);
        effective.requireEditAll(userId, batch);
        // 지우기 전에 남긴다 — 지운 뒤에는 제목을 읽을 수 없다.
        audit.recordPage(userId, com.platform.wikibackend.domain.AuditAction.PAGE_PURGED, root,
                batch.size() > 1 ? "하위 " + (batch.size() - 1) + "개 함께" : null);
        hardDelete(userId, batch);
    }

    /** 휴지통 비우기 — 사용자가 볼 수 있는 루트 묶음만 지운다. */
    public int empty(long userId, long spaceId) {
        spaces.require(userId, spaceId, WikiAction.ADMIN);
        List<TrashRow> rows = trashRows(spaceId);
        List<Long> rootIds = rows.stream()
                .filter(TrashRow::deletedRoot)
                .map(TrashRow::id)
                .toList();
        int purged = 0;
        for (Long rootId : rootIds) {
            Page root = pages.findAnyById(rootId).orElse(null);
            if (root == null || root.getDeletedAt() == null) continue;
            List<Page> batch = restorableBatch(root);
            effective.requireEditAll(userId, batch);
            audit.recordPage(userId, com.platform.wikibackend.domain.AuditAction.PAGE_PURGED, root,
                    "휴지통 비우기");
            hardDelete(userId, batch);
            purged += batch.size();
        }
        return purged;
    }

    /**
     * 보존 기간 경과분 자동 영구 삭제(스케줄러 전용) — 권한 검사가 없다.
     * 사용자 요청이 아니라 정책 실행이고, 실행 주체가 될 사용자가 없기 때문이다.
     */
    public int purgeExpired(java.time.Instant before) {
        List<Long> expired = pages.findExpiredTrashRootIds(before);
        int purged = 0;
        for (Long rootId : expired) {
            Page root = pages.findAnyById(rootId).orElse(null);
            if (root == null || root.getDeletedAt() == null) continue;
            List<Page> batch = restorableBatch(root);
            hardDelete(root.getDeletedBy(), batch);
            purged += batch.size();
        }
        if (purged > 0) log.info("휴지통 보존 기간 경과 영구 삭제: {}건", purged);
        return purged;
    }

    /* ── 내부 ────────────────────────────────────────────── */

    private List<TrashRow> trashRows(long spaceId) {
        return pages.findTrashedRows(spaceId).stream().map(TrashRow::from).toList();
    }

    private Page trashed(long pageId) {
        Page p = pages.findAnyById(pageId)
                .orElseThrow(() -> new NotFoundException("페이지 없음: " + pageId));
        if (p.getDeletedAt() == null) {
            throw new NotFoundException("휴지통에 없는 페이지입니다: " + pageId);
        }
        return p;
    }

    /**
     * 루트와 "루트가 아닌" 버려진 자손. 자손 중 deletedRoot=true인 노드는 그 자신이 별도
     * 휴지통 항목이므로 묶음에서 제외하고, 그 아래도 따라가지 않는다.
     */
    private List<Page> restorableBatch(Page root) {
        Map<Long, List<TrashRow>> childrenOf = new HashMap<>();
        for (TrashRow row : trashRows(root.getSpaceId())) {
            childrenOf.computeIfAbsent(row.parentId(), k -> new ArrayList<>()).add(row);
        }
        List<Long> ids = new ArrayList<>();
        Set<Long> visited = new HashSet<>(Set.of(root.getId()));
        Deque<Long> queue = new ArrayDeque<>(List.of(root.getId()));
        while (!queue.isEmpty()) {
            Long cursor = queue.poll();
            for (TrashRow child : childrenOf.getOrDefault(cursor, List.of())) {
                if (child.deletedRoot()) continue; // 따로 버린 묶음 — 경계
                if (!visited.add(child.id())) continue; // 손상 데이터(parent 순환) 방어
                ids.add(child.id());
                queue.add(child.id());
            }
        }
        List<Page> batch = new ArrayList<>();
        batch.add(root);
        if (!ids.isEmpty()) batch.addAll(pages.findAnyByIdIn(ids));
        return batch;
    }

    private int countDescendants(Long rootId, Map<Long, List<TrashRow>> childrenOf) {
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

    /** 목록 필터가 쓰는 제한 판정용 엔티티 로드 — 행은 이미 스페이스 스코프다. */
    private List<Page> loadForPermission(List<TrashRow> rows) {
        return pages.findAnyByIdIn(rows.stream().map(TrashRow::id).toList());
    }

    /** 되돌릴 수 없는 삭제 — 첨부 객체·리비전·댓글·제한까지 전부. */
    private void hardDelete(Long actorId, List<Page> batch) {
        // 자손부터 지운다 — 부모를 먼저 지우면 운영 PG의 FK cascade가 자식을 먼저 없애
        // 이어지는 개별 DELETE가 0행이 되고 Hibernate가 StaleStateException을 던진다.
        List<Page> ordered = new ArrayList<>(batch);
        java.util.Collections.reverse(ordered);
        for (Page p : ordered) {
            long pageId = p.getId();
            for (Attachment a : attachments.findByPageId(pageId)) {
                storage.deleteAfterCommit(a.getStorageBackend(), a.getStorageBucket(),
                        a.getStorageKey(), a.getStorageVersion());
            }
            attachments.deleteByPageId(pageId);
            // H2 테스트 스키마엔 FK가 없다(Long 컬럼만) — 명시 삭제로 고아를 막는다.
            revisions.deleteByPageId(pageId);
            // 리액션은 FK 없이 (type, id)로 매달려 있다 — 댓글을 지우기 전에 걷어낸다(W23).
            for (com.platform.wikibackend.domain.PageComment c : comments.findByPageIdOrderByCreatedAtAscIdAsc(pageId)) {
                reactionRepository.deleteByTargetTypeAndTargetId("COMMENT", c.getId());
            }
            reactionRepository.deleteByTargetTypeAndTargetId("PAGE", pageId);
            comments.deleteByPageId(pageId);
            taskRepository.deleteByPageId(pageId);
            restrictions.deleteByPageId(pageId);
            labels.deleteByPageId(pageId);
            links.deleteBySourcePageId(pageId);
            watches.deleteByPageId(pageId);
            events.afterCommit(WikiEvents.pageDeleted(
                    actorId == null ? 0L : actorId, pageId, p.getSpaceId()));
        }
        pages.deleteAllByIdIncludingTrashed(ordered.stream().map(Page::getId).toList());
    }
}
