package com.platform.wikibackend.page;

import com.platform.common.error.NotFoundException;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageType;
import com.platform.wikibackend.event.EventRelay;
import com.platform.wikibackend.event.WikiEvents;
import com.platform.wikibackend.page.dto.PageMoveRequest;
import com.platform.wikibackend.page.dto.PageResponse;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.permission.MoveImpactException;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.space.SpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 트리 이동·재정렬·스페이스 간 이동 — 버전을 올리지 않는 구조 변경. PageService에서 분리(2026-10-18). */
@Service
@RequiredArgsConstructor
@Transactional
public class PageMoveService {

    private final PageRepository pages;
    private final SpaceService spaces;
    private final EffectivePermissionService effective;
    private final EventRelay events;
    private final PageStructure structure;

    /**
     * 트리 이동/재정렬(P1-001) — 부모 변경과 형제 순서를 한 트랜잭션에서 처리한다.
     * 내용 편집이 아니므로 version을 올리지 않고 리비전도 쌓지 않는다(스토어 계약).
     * 스페이스 행을 잠가 같은 스페이스의 동시 재정렬과 직렬화한다.
     */
    public PageResponse move(long userId, long pageId, PageMoveRequest req) {
        Page page = structure.getOwned(pageId);
        spaces.require(userId, page.getSpaceId(), WikiAction.EDIT);
        effective.requireEdit(userId, page);
        PageWriteGuard.requireWritable(page);
        if (page.getType() == PageType.BLOG) {
            throw new IllegalArgumentException("블로그 글은 트리에 속하지 않아 옮길 수 없습니다");
        }
        // W18 이동 영향(설계 §5) — 부모/스페이스가 실제로 바뀔 때, 새 조상의 VIEW 제한이 새로
        // 적용되면 확인 없이는 409. 순수 재정렬(같은 부모)은 영향이 없다.
        long targetSpaceId = req.spaceId() != null ? req.spaceId() : page.getSpaceId();
        boolean relocated = targetSpaceId != page.getSpaceId()
                || !Objects.equals(page.getParentId(), req.parentId());
        // 대상 스페이스·부모를 먼저 인가해야 409 impact가 숨겨진 부모 제목/주체를 누출하지 않는다.
        spaces.require(userId, targetSpaceId, WikiAction.EDIT);
        structure.requireEditableTargetParent(userId, targetSpaceId, req.parentId());
        if (relocated) {
            // 부모 변경은 서브트리 전체의 effective VIEW를 바꾼다. 숨겨진 자손을 대신 옮기지 못하게
            // 실제 변경 대상 전부를 mutation 전에 판정한다.
            effective.requireEditAll(userId, structure.collectSubtree(pageId));
        }
        if (relocated && !req.impactConfirmed()) {
            var impact = effective.newViewRestrictionsAfterMove(page, req.parentId());
            if (!impact.isEmpty()) {
                throw new MoveImpactException(impact);
            }
        }
        if (req.spaceId() != null && !Objects.equals(req.spaceId(), page.getSpaceId())) {
            return moveToSpace(userId, pageId, req);
        }
        spaces.lockForReorder(page.getSpaceId());
        Page locked = pages.findByIdForUpdate(pageId)
                .orElseThrow(() -> new NotFoundException("페이지 없음: " + pageId));
        if (!Objects.equals(locked.getParentId(), req.parentId())) {
            structure.validateParent(locked.getSpaceId(), req.parentId(), pageId);
        }
        Long previousParentId = locked.getParentId();
        boolean regrouped = !Objects.equals(previousParentId, req.parentId());

        List<Page> group = new ArrayList<>(pages.findSiblings(locked.getSpaceId(), req.parentId()));
        group.removeIf(sibling -> sibling.getId().equals(pageId));
        int insertAt = -1;
        if (req.beforeId() != null) {
            for (int i = 0; i < group.size(); i++) {
                if (group.get(i).getId().equals(req.beforeId())) { insertAt = i; break; }
            }
        }
        // beforeId가 그룹에 없으면(드래그 중 stale 참조) 조용히 맨 뒤 — 화면은 이동 후 재조회한다
        if (insertAt < 0) insertAt = group.size();
        locked.rankTo(req.parentId(), locked.getSortOrder());
        group.add(insertAt, locked);
        for (int i = 0; i < group.size(); i++) group.get(i).resequence(i + 1L);
        if (regrouped) {
            structure.resequenceSiblings(locked.getSpaceId(), previousParentId, pageId);
            // 부모가 바뀐 사실이 나가지 않으면 색인·활동피드가 스테일해진다(순수 재정렬은 색인 무관)
            events.afterCommit(WikiEvents.pageUpdated(userId, locked));
        }
        return PageResponse.from(locked);
    }

    /**
     * 스페이스 간 이동 — 양쪽 스페이스 EDIT가 필요하고, 하위 처리는 요청이 정한다
     * (children=with: 서브트리 동반 / promote: 하위는 원래 부모 밑에 남김).
     * 첨부·댓글·리비전은 pageId에 묶여 있어 행 이동이 필요 없고, 검색 색인은 스페이스가
     * 권한 필터라서 이동한 모든 페이지에 pageUpdated를 다시 발행한다.
     */
    private PageResponse moveToSpace(long userId, long pageId, PageMoveRequest req) {
        Long targetSpaceId = req.spaceId();
        spaces.require(userId, targetSpaceId, WikiAction.EDIT);
        // 두 스페이스를 id 순서로 잠근다 — 반대 방향 이동과의 교착 방지
        Page snapshot = structure.getOwned(pageId);
        Long sourceSpaceId = snapshot.getSpaceId();
        long first = Math.min(sourceSpaceId, targetSpaceId);
        long second = Math.max(sourceSpaceId, targetSpaceId);
        spaces.lockForReorder(first);
        spaces.lockForReorder(second);
        Page locked = pages.findByIdForUpdate(pageId)
                .orElseThrow(() -> new NotFoundException("페이지 없음: " + pageId));

        // 대상 부모는 대상 스페이스 소속이어야 한다
        if (req.parentId() != null) {
            Page targetParent = pages.findById(req.parentId())
                    .orElseThrow(() -> new NotFoundException("부모 페이지 없음: " + req.parentId()));
            if (!Objects.equals(targetParent.getSpaceId(), targetSpaceId)) {
                throw new IllegalArgumentException("부모 페이지가 대상 스페이스에 없습니다");
            }
        }

        List<Page> subtree = structure.collectSubtree(pageId);
        if (req.parentId() != null && !req.promoteChildren()
                && subtree.stream().anyMatch(p -> p.getId().equals(req.parentId()))) {
            throw new IllegalArgumentException("페이지를 자신의 하위로 이동할 수 없습니다");
        }

        Long previousParentId = locked.getParentId();
        if (req.promoteChildren()) {
            // 직계 하위를 원래 부모 밑으로 올린다(삭제 PROMOTE와 같은 의미론)
            long nextOrder = pages.findMaxSortOrder(sourceSpaceId, previousParentId);
            for (Page child : pages.findByParentId(pageId)) {
                child.rankTo(previousParentId, ++nextOrder);
                events.afterCommit(WikiEvents.pageUpdated(userId, child));
            }
        } else {
            // 서브트리 동반 — 구조(부모 관계·형제 순서)는 유지하고 spaceId만 바꾼다
            for (Page descendant : subtree) {
                if (descendant.getId().equals(pageId)) continue;
                descendant.moveToSpace(targetSpaceId, descendant.getParentId(), descendant.getSortOrder());
                events.afterCommit(WikiEvents.pageUpdated(userId, descendant));
            }
        }

        long order = pages.findMaxSortOrder(targetSpaceId, req.parentId()) + 1;
        locked.moveToSpace(targetSpaceId, req.parentId(), order);
        structure.resequenceSiblings(sourceSpaceId, previousParentId, pageId);
        events.afterCommit(WikiEvents.pageUpdated(userId, locked));
        return PageResponse.from(locked);
    }
}
