package com.platform.wikibackend.page;

import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import com.platform.wikibackend.audit.AuditService;
import com.platform.wikibackend.collaboration.CollaborationDraftReset;
import com.platform.wikibackend.common.ActorNames;
import com.platform.wikibackend.domain.AuditAction;
import com.platform.wikibackend.domain.CollaborationDraftMetadata;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRevision;
import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.domain.PageType;
import com.platform.wikibackend.event.EventRelay;
import com.platform.wikibackend.event.WikiEvents;
import com.platform.wikibackend.label.LabelService;
import com.platform.wikibackend.notification.NotificationService;
import com.platform.wikibackend.page.dto.CollaborationDraftCommitRequest;
import com.platform.wikibackend.page.dto.CollaborationDraftCommitResponse;
import com.platform.wikibackend.page.dto.PageCreateRequest;
import com.platform.wikibackend.page.dto.PageResponse;
import com.platform.wikibackend.page.dto.PageUpdateRequest;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.CollaborationDraftMetadataRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.space.SpaceService;
import com.platform.wikibackend.task.TaskService;
import com.platform.wikibackend.watch.WatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 페이지 본문의 생명주기 — 생성·조회·수정(새 버전)·공동 초안 게시·휴지통 보내기·게시.
 *
 * 2026-10-18에 도메인별로 나눴다: 복사(PageCopyService), 이동(PageMoveService), 메타데이터
 * (PageMetaService), 이력·복원(RevisionService), 구조 공용 연산(PageStructure). 버전을 올리는 경로가
 * 흩어져 한 경로만 검사가 빠지던 결함(W-01·W-07)을 줄이려고, 쓰기 경로마다 PageWriteGuard를 부른다.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PageService {

    private final PageRepository pages;
    private final PageRevisionRepository revisions;
    private final SpaceService spaces;
    private final NotificationService notificationService;
    private final EffectivePermissionService effective;
    private final EventRelay events;
    private final CollaborationDraftMetadataRepository collaborationDrafts;
    private final LabelService labelService;
    private final AuditService audit;
    private final WatchService watches;
    private final ActorNames actorNames;
    private final TaskService taskSync;
    private final CollaborationDraftReset collaborationReset;
    private final PageStructure structure;

    public PageResponse create(long userId, PageCreateRequest req) {
        spaces.require(userId, req.spaceId(), WikiAction.EDIT);
        structure.validateParent(req.spaceId(), req.parentId(), null);
        if (req.type() == PageType.BLOG && req.parentId() != null) {
            throw new IllegalArgumentException("블로그 글은 트리에 넣을 수 없습니다");
        }
        structure.requireEditableTargetParent(userId, req.spaceId(), req.parentId());
        Page saved = pages.save(Page.of(req.spaceId(), req.parentId(), req.title(), req.content(), userId,
                req.type(), req.status()));
        saved.resequence(pages.findMaxSortOrder(req.spaceId(), req.parentId()) + 1); // 형제 맨 뒤(V9)
        revisions.save(PageRevision.snapshotOf(saved).withEditorName(actorNames.current())); // 버전1도 리비전에 — "모든 버전이 리비전에 있다"
        taskSync.sync(saved); // 작업 표는 본문의 파생물(W23) — 리비전과 같은 자리에서 갱신
        labelService.reindexLinks(saved); // 백링크 그래프(V14)는 본문의 파생물 — 저장과 같은 트랜잭션에서 갱신
        watches.autoWatch(saved.getId(), userId); // 만든 문서는 자동 구독(W21-4)
        // 곧바로 게시된 문서는 그 순간이 곧 "새 문서 게시"다 — 초안으로 만들었다가 게시하는
        // 경로(publish)만 알리면 대부분의 문서가 스페이스 구독자에게 조용히 지나간다(W27-4).
        // 폴더는 읽을 내용이 없으므로 제외한다.
        if (saved.getStatus() == PageStatus.PUBLISHED
                && saved.getType() != PageType.FOLDER) {
            notificationService.onPagePublished(userId, saved);
        }
        events.afterCommit(WikiEvents.pageCreated(userId, saved));
        return PageResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse get(long userId, long pageId) {
        Page p = getOwned(pageId);
        spaces.require(userId, p.getSpaceId(), WikiAction.VIEW);
        effective.requireView(userId, p);
        return PageResponse.from(p);
    }

    /** 수정 = 새 버전. expectedVersion 불일치 409. 부모 변경은 이동 API(PageMoveService). */
    public PageResponse update(long userId, long pageId, PageUpdateRequest req) {
        // page → 공동 초안 순서로 잠근다(게시와 같은 순서) — 동시 게시와 같은 버전 리비전을 두 번 만들지 않는다.
        Page p = structure.lockOwned(pageId);
        spaces.require(userId, p.getSpaceId(), WikiAction.EDIT);
        effective.requireEdit(userId, p);
        PageWriteGuard.requireWritable(p);
        if (!Objects.equals(p.getVersion(), req.expectedVersion())) {
            throw new ConflictException("버전 충돌 — 현재 " + p.getVersion() + ", 요청 " + req.expectedVersion());
        }
        if (!Objects.equals(p.getParentId(), req.parentId())) {
            // 부모 변경은 영향 확인·대상 부모 인가가 있는 전용 API만 허용한다. PUT으로 허용하면
            // confirmImpact 없이 제한 조상 아래로 이동할 수 있다.
            throw new IllegalArgumentException("부모 변경은 페이지 이동 API를 사용해야 합니다");
        }
        String oldBody = p.getContent();
        p.edit(req.title(), req.content(), userId);
        collaborationReset.afterExternalWrite(p); // 공동 초안이 옛 base에 묶이지 않게(차단-1)
        revisions.save(PageRevision.snapshotOf(p, req.changeNote()).withEditorName(actorNames.current()));
        taskSync.sync(p); // 작업 표는 본문의 파생물(W23) — 일반 저장이 빠지면 줄 번호 토글이 남의 항목을 바꾼다(W-01)
        labelService.reindexLinks(p);
        watches.autoWatch(pageId, userId); // 고친 문서는 자동 구독(W21-4)
        events.afterCommit(WikiEvents.pageUpdated(userId, p));
        notificationService.onPageUpdated(userId, p, oldBody, req.content());
        return PageResponse.from(p);
    }

    /**
     * 사용자가 현재 보고 있는 Yjs projection을 게시 revision으로 확정한다. page와 collaboration metadata를
     * 같은 DB transaction/row lock으로 전진시켜 두 동시 저장과 이전 generation의 늦은 요청을 막는다.
     */
    public CollaborationDraftCommitResponse commitCollaborationDraft(
            long userId,
            long pageId,
            CollaborationDraftCommitRequest req) {
        // 모든 writer가 page → collaboration 순서로 잠가 교착을 피한다.
        Page page = pages.findByIdForUpdate(pageId)
                .orElseThrow(() -> new NotFoundException("페이지 없음: " + pageId));
        spaces.require(userId, page.getSpaceId(), WikiAction.EDIT);
        effective.requireEdit(userId, page);
        PageWriteGuard.requireWritable(page); // 이미 열린 공동 편집 세션으로 보관 문서가 바뀌지 않게
        CollaborationDraftMetadata draft = collaborationDrafts
                .findByRoomForUpdate(CollaborationDraftMetadata.room(pageId))
                .orElseThrow(() -> new ConflictException("공동 초안이 준비되지 않았습니다"));

        if (!Objects.equals(page.getVersion(), req.expectedPageVersion())
                || !Objects.equals(draft.getBasePageVersion(), req.expectedPageVersion().longValue())
                || !Objects.equals(draft.getGeneration(), req.expectedGeneration())) {
            throw new ConflictException("공동 초안 버전이 변경되었습니다. 동기화 후 다시 저장하세요");
        }

        String oldBody = page.getContent();
        page.edit(req.title(), req.content(), userId);
        draft.advanceTo(page.getVersion());
        revisions.save(PageRevision.snapshotOf(page).withEditorName(actorNames.current()));
        taskSync.sync(page); // 작업 표는 본문의 파생물(W23) — 리비전과 같은 자리에서 갱신
        labelService.reindexLinks(page);
        watches.autoWatch(page.getId(), userId);
        events.afterCommit(WikiEvents.pageUpdated(userId, page));
        notificationService.onPageUpdated(userId, page, oldBody, req.content());
        return new CollaborationDraftCommitResponse(PageResponse.from(page), draft.getGeneration());
    }

    /**
     * 자식 처리는 호출측이 고른다(기획 P2). policy가 null인데 자식이 있으면 거부한다 —
     * 옵션 없는 삭제가 하위를 통째로 날리면 호출 실수 한 번이 문서 트리를 지운다.
     *
     * W21-1부터 **소프트 삭제**다. 본문·리비전·댓글·첨부 객체는 그대로 두고 deleted_at만 찍는다.
     * 되돌릴 수 없는 삭제는 TrashService.purge(스페이스 ADMIN)만 할 수 있다.
     */
    public void delete(long userId, long pageId, ChildrenPolicy policy) {
        Page p = getOwned(pageId);
        spaces.require(userId, p.getSpaceId(), WikiAction.EDIT);
        effective.requireEdit(userId, p);

        List<Page> children = pages.findByParentId(pageId);
        if (!children.isEmpty()) {
            if (policy == null) {
                throw new ConflictException("하위 페이지가 있어 삭제할 수 없습니다");
            }
            // CASCADE는 자손을 삭제하고 PROMOTE도 자손의 제한 상속 체인을 바꾼다. 루트만 검사하면
            // 사용자가 볼 수 없는 제한 자손을 삭제·재배치할 수 있으므로 전체를 먼저 판정한다.
            effective.requireEditAll(userId, structure.collectSubtree(pageId));
            if (policy == ChildrenPolicy.PROMOTE) {
                // 대상의 부모로 올린다. 부모는 자식의 조상이므로 순환이 생길 수 없다(검증 불필요).
                long nextOrder = pages.findMaxSortOrder(p.getSpaceId(), p.getParentId());
                for (Page child : children) {
                    child.rankTo(p.getParentId(), ++nextOrder);
                    // 부모가 바뀐 사실이 나가지 않으면 색인·활동피드가 스테일해진다
                    events.afterCommit(WikiEvents.pageUpdated(userId, child));
                }
                pages.saveAll(children);
            } else {
                trashSubtree(userId, children, new HashSet<>(Set.of(pageId)));
            }
        }
        trashOne(userId, p, true);
    }

    /**
     * 후손 전부를 휴지통으로. visited는 손상 데이터(parent_id 순환)에서 무한 재귀에 빠지지 않게
     * 한다 — 정상 경로로는 validateParent가 순환을 막지만, 데이터가 깨지면 여기가 스레드를 잡아먹는다.
     */
    private void trashSubtree(long userId, List<Page> level, Set<Long> visited) {
        for (Page child : level) {
            if (!visited.add(child.getId())) continue; // 이미 처리한 노드 — 순환
            trashSubtree(userId, pages.findByParentId(child.getId()), visited);
            trashOne(userId, child, false);
        }
    }

    /**
     * 페이지 하나를 휴지통으로 보낸다(V13). 첨부 객체·리비전·댓글은 복원을 위해 남긴다.
     * 검색 색인에서는 즉시 내려야 하므로 pageDeleted 이벤트는 그대로 발행한다 —
     * 복원 시 TrashService가 pageUpdated로 다시 올린다.
     */
    private void trashOne(long userId, Page p, boolean root) {
        if (root) audit.recordPage(userId, AuditAction.PAGE_TRASHED, p, null);
        p.moveToTrash(userId, root);
        events.afterCommit(WikiEvents.pageDeleted(userId, p.getId(), p.getSpaceId()));
    }

    /** 초안 게시. 이미 게시됐으면 멱등 — 버전·리비전을 건드리지 않는다(내용 변경이 아니다). */
    public PageResponse publish(long userId, long pageId) {
        Page p = getOwned(pageId);
        spaces.require(userId, p.getSpaceId(), WikiAction.EDIT);
        effective.requireEdit(userId, p);
        PageWriteGuard.requireWritable(p);
        if (p.getStatus() != PageStatus.PUBLISHED) {
            p.publish();
            events.afterCommit(WikiEvents.pageUpdated(userId, p));
            notificationService.onPagePublished(userId, p); // 스페이스 구독자가 기다리던 사건(W27-4)
        }
        return PageResponse.from(p);
    }


    /** 협업 티켓 등 외부 서비스용 — space EDIT + 페이지 제한(effective)을 한 번에 통과시킨다. */
    @Transactional(readOnly = true)
    public Page getEditable(long userId, long pageId) {
        Page p = getOwned(pageId);
        spaces.require(userId, p.getSpaceId(), WikiAction.EDIT);
        effective.requireEdit(userId, p);
        PageWriteGuard.requireWritable(p);
        return p;
    }

    /** 존재 검증만 — 권한은 호출부가. 첨부(Task 11)·리비전(Task 10)이 재사용. */
    @Transactional(readOnly = true)
    public Page getOwned(long pageId) {
        return structure.getOwned(pageId);
    }
}
