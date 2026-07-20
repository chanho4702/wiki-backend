package com.platform.wikibackend.page;

import com.platform.wikibackend.common.ConflictException;
import com.platform.wikibackend.common.NotFoundException;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRevision;
import com.platform.wikibackend.event.EventRelay;
import com.platform.wikibackend.event.WikiEvents;
import com.platform.wikibackend.page.dto.PageCreateRequest;
import com.platform.wikibackend.page.dto.PageResponse;
import com.platform.wikibackend.page.dto.PageTreeItem;
import com.platform.wikibackend.page.dto.PageUpdateRequest;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.space.SpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional
public class PageService {

    private final PageRepository pages;
    private final PageRevisionRepository revisions;
    private final SpaceService spaces;
    private final EventRelay events;

    public PageResponse create(long userId, PageCreateRequest req) {
        spaces.require(userId, req.spaceId(), WikiAction.EDIT);
        validateParent(req.spaceId(), req.parentId(), null);
        Page saved = pages.save(Page.of(req.spaceId(), req.parentId(), req.title(), req.content(), userId));
        revisions.save(PageRevision.snapshotOf(saved)); // 버전1도 리비전에 — "모든 버전이 리비전에 있다"
        events.afterCommit(WikiEvents.pageCreated(userId, saved));
        return PageResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse get(long userId, long pageId) {
        Page p = getOwned(pageId);
        spaces.require(userId, p.getSpaceId(), WikiAction.VIEW);
        return PageResponse.from(p);
    }

    @Transactional(readOnly = true)
    public List<PageTreeItem> tree(long userId, long spaceId) {
        spaces.getForView(userId, spaceId);
        return pages.findBySpaceIdOrderById(spaceId).stream().map(PageTreeItem::from).toList();
    }

    /** 수정 = 새 버전. expectedVersion 불일치 409. parentId 변경은 이동(순환 검증). */
    public PageResponse update(long userId, long pageId, PageUpdateRequest req) {
        Page p = getOwned(pageId);
        spaces.require(userId, p.getSpaceId(), WikiAction.EDIT);
        if (!Objects.equals(p.getVersion(), req.expectedVersion())) {
            throw new ConflictException("버전 충돌 — 현재 " + p.getVersion() + ", 요청 " + req.expectedVersion());
        }
        if (!Objects.equals(p.getParentId(), req.parentId())) {
            validateParent(p.getSpaceId(), req.parentId(), pageId);
            p.moveTo(req.parentId());
        }
        p.edit(req.title(), req.content(), userId);
        revisions.save(PageRevision.snapshotOf(p));
        events.afterCommit(WikiEvents.pageUpdated(userId, p));
        return PageResponse.from(p);
    }

    public void delete(long userId, long pageId) {
        Page p = getOwned(pageId);
        spaces.require(userId, p.getSpaceId(), WikiAction.EDIT);
        long spaceId = p.getSpaceId();

        // 하위 페이지들을 먼저 삭제 (cascade)
        List<Page> children = pages.findByParentId(pageId);
        for (Page child : children) {
            delete(userId, child.getId());
        }

        pages.delete(p); // cascade: 리비전·첨부 (첨부 파일 정리는 Task 11)
        events.afterCommit(WikiEvents.pageDeleted(userId, pageId, spaceId));
    }

    /** 존재 검증만 — 권한은 호출부가. 첨부(Task 11)·리비전(Task 10)이 재사용. */
    @Transactional(readOnly = true)
    public Page getOwned(long pageId) {
        return pages.findById(pageId).orElseThrow(() -> new NotFoundException("페이지 없음: " + pageId));
    }

    /** parent는 같은 스페이스 + (이동 시) 자기 자신·자손 금지. */
    private void validateParent(Long spaceId, Long parentId, Long movingPageId) {
        if (parentId == null) return;
        Page parent = pages.findById(parentId).orElseThrow(() -> new IllegalArgumentException("부모 페이지 없음: " + parentId));
        if (!Objects.equals(parent.getSpaceId(), spaceId)) {
            throw new IllegalArgumentException("부모는 같은 스페이스여야 합니다");
        }
        if (movingPageId != null) {
            Long cursor = parentId;
            while (cursor != null) {
                if (Objects.equals(cursor, movingPageId)) throw new IllegalArgumentException("자기 자신/자손 아래로 이동 불가");
                cursor = pages.findById(cursor).map(Page::getParentId).orElse(null);
            }
        }
    }
}
