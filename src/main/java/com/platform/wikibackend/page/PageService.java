package com.platform.wikibackend.page;

import com.platform.wikibackend.attachment.LocalFileStorage;
import com.platform.wikibackend.common.ConflictException;
import com.platform.wikibackend.common.NotFoundException;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRevision;
import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.event.EventRelay;
import com.platform.wikibackend.event.WikiEvents;
import com.platform.wikibackend.page.dto.PageCreateRequest;
import com.platform.wikibackend.page.dto.PageResponse;
import com.platform.wikibackend.page.dto.PageTreeItem;
import com.platform.wikibackend.page.dto.PageUpdateRequest;
import com.platform.wikibackend.page.dto.RevisionMeta;
import com.platform.wikibackend.page.dto.RevisionResponse;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.space.SpaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PageService {

    private final PageRepository pages;
    private final PageRevisionRepository revisions;
    private final SpaceService spaces;
    private final EventRelay events;
    private final AttachmentRepository attachments;
    private final LocalFileStorage storage;

    public PageResponse create(long userId, PageCreateRequest req) {
        spaces.require(userId, req.spaceId(), WikiAction.EDIT);
        validateParent(req.spaceId(), req.parentId(), null);
        Page saved = pages.save(Page.of(req.spaceId(), req.parentId(), req.title(), req.content(), userId,
                req.type(), req.status()));
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

    /**
     * 자식 처리는 호출측이 고른다(기획 P2). policy가 null인데 자식이 있으면 거부한다 —
     * 옵션 없는 삭제가 하위를 통째로 날리면 호출 실수 한 번이 문서 트리를 지운다.
     */
    public void delete(long userId, long pageId, ChildrenPolicy policy) {
        Page p = getOwned(pageId);
        spaces.require(userId, p.getSpaceId(), WikiAction.EDIT);

        List<Page> children = pages.findByParentId(pageId);
        if (!children.isEmpty()) {
            if (policy == null) {
                throw new ConflictException("하위 페이지가 있어 삭제할 수 없습니다");
            }
            if (policy == ChildrenPolicy.PROMOTE) {
                // 대상의 부모로 올린다. 부모는 자식의 조상이므로 순환이 생길 수 없다(검증 불필요).
                for (Page child : children) {
                    child.moveTo(p.getParentId());
                    // 부모가 바뀐 사실이 나가지 않으면 색인·활동피드가 스테일해진다
                    events.afterCommit(WikiEvents.pageUpdated(userId, child));
                }
                pages.saveAll(children);
            } else {
                deleteSubtree(userId, children, new HashSet<>(Set.of(pageId)));
            }
        }
        deleteOne(userId, p);
    }

    /**
     * 후손 전부 삭제. visited는 손상 데이터(parent_id 순환)에서 무한 재귀에 빠지지 않게 한다 —
     * 정상 경로로는 validateParent가 순환을 막지만, 데이터가 깨지면 여기가 스레드를 잡아먹는다.
     */
    private void deleteSubtree(long userId, List<Page> level, Set<Long> visited) {
        for (Page child : level) {
            if (!visited.add(child.getId())) continue; // 이미 지운 노드 — 순환
            deleteSubtree(userId, pages.findByParentId(child.getId()), visited);
            deleteOne(userId, child);
        }
    }

    /** 페이지 하나와 그에 딸린 첨부·리비전을 지운다. */
    private void deleteOne(long userId, Page p) {
        long pageId = p.getId();
        long spaceId = p.getSpaceId();

        // 첨부 파일 정리 — 디스크 파일과 DB 행 모두
        attachments.findByPageId(pageId).forEach(a -> {
            if (!storage.delete(a.getStorageKey())) {
                log.warn("첨부 파일 삭제 실패(고아 파일 — 무해): key={}", a.getStorageKey());
            }
        });
        attachments.deleteByPageId(pageId);

        // 리비전 명시 삭제 — H2 테스트 스키마는 FK 없음(Long 컬럼만) → 고아 방지
        revisions.deleteByPageId(pageId);
        pages.delete(p);
        events.afterCommit(WikiEvents.pageDeleted(userId, pageId, spaceId));
    }

    /** 초안 게시. 이미 게시됐으면 멱등 — 버전·리비전을 건드리지 않는다(내용 변경이 아니다). */
    public PageResponse publish(long userId, long pageId) {
        Page p = getOwned(pageId);
        spaces.require(userId, p.getSpaceId(), WikiAction.EDIT);
        if (p.getStatus() != PageStatus.PUBLISHED) {
            p.publish();
            events.afterCommit(WikiEvents.pageUpdated(userId, p));
        }
        return PageResponse.from(p);
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
            // visited: 손상 데이터(parent_id 순환)에서 무한 루프하지 않는다 — 삭제 경로와 같은 방어
            Set<Long> visited = new HashSet<>();
            Long cursor = parentId;
            while (cursor != null && visited.add(cursor)) {
                if (Objects.equals(cursor, movingPageId)) throw new IllegalArgumentException("자기 자신/자손 아래로 이동 불가");
                cursor = pages.findById(cursor).map(Page::getParentId).orElse(null);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<RevisionMeta> listRevisions(long userId, long pageId) {
        Page p = getOwned(pageId);
        spaces.require(userId, p.getSpaceId(), WikiAction.VIEW);
        return revisions.findByPageIdOrderByVersionDesc(pageId).stream().map(RevisionMeta::from).toList();
    }

    @Transactional(readOnly = true)
    public RevisionResponse getRevision(long userId, long pageId, int version) {
        Page p = getOwned(pageId);
        spaces.require(userId, p.getSpaceId(), WikiAction.VIEW);
        return revisions.findByPageIdAndVersion(pageId, version)
                .map(RevisionResponse::from)
                .orElseThrow(() -> new NotFoundException("리비전 없음: v" + version));
    }

    /** 복원 = 해당 리비전 내용으로 새 버전 생성(이력 보존 — 스펙). */
    public PageResponse restore(long userId, long pageId, int version) {
        Page p = getOwned(pageId);
        spaces.require(userId, p.getSpaceId(), WikiAction.EDIT);
        PageRevision target = revisions.findByPageIdAndVersion(pageId, version)
                .orElseThrow(() -> new NotFoundException("리비전 없음: v" + version));
        p.edit(target.getTitle(), target.getContent(), userId);
        revisions.save(PageRevision.snapshotOf(p));
        events.afterCommit(WikiEvents.pageUpdated(userId, p));
        return PageResponse.from(p);
    }
}
