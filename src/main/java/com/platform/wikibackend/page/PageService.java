package com.platform.wikibackend.page;

import com.platform.wikibackend.attachment.AttachmentStorageRouter;
import com.platform.wikibackend.common.ConflictException;
import com.platform.wikibackend.common.NotFoundException;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRevision;
import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.domain.CollaborationDraftMetadata;
import com.platform.wikibackend.event.EventRelay;
import com.platform.wikibackend.event.WikiEvents;
import com.platform.wikibackend.page.dto.PageCreateRequest;
import com.platform.wikibackend.page.dto.PageMoveRequest;
import com.platform.wikibackend.page.dto.CollaborationDraftCommitRequest;
import com.platform.wikibackend.page.dto.CollaborationDraftCommitResponse;
import com.platform.wikibackend.page.dto.PageResponse;
import com.platform.wikibackend.page.dto.PageTreeItem;
import com.platform.wikibackend.page.dto.PageUpdateRequest;
import com.platform.wikibackend.page.dto.RevisionMeta;
import com.platform.wikibackend.page.dto.RevisionResponse;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.attachment.AttachmentLifecycleStatus;
import com.platform.wikibackend.attachment.AttachmentReferences;
import com.platform.wikibackend.attachment.StoredObject;
import com.platform.wikibackend.domain.Attachment;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.repository.CollaborationDraftMetadataRepository;
import com.platform.wikibackend.repository.PageCommentRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.space.SpaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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
    private final PageCommentRepository comments;
    private final PageRevisionRepository revisions;
    private final SpaceService spaces;
    private final EventRelay events;
    private final AttachmentRepository attachments;
    private final AttachmentStorageRouter storage;
    private final CollaborationDraftMetadataRepository collaborationDrafts;

    public PageResponse create(long userId, PageCreateRequest req) {
        spaces.require(userId, req.spaceId(), WikiAction.EDIT);
        validateParent(req.spaceId(), req.parentId(), null);
        Page saved = pages.save(Page.of(req.spaceId(), req.parentId(), req.title(), req.content(), userId,
                req.type(), req.status()));
        saved.resequence(pages.findMaxSortOrder(req.spaceId(), req.parentId()) + 1); // 형제 맨 뒤(V9)
        revisions.save(PageRevision.snapshotOf(saved)); // 버전1도 리비전에 — "모든 버전이 리비전에 있다"
        events.afterCommit(WikiEvents.pageCreated(userId, saved));
        return PageResponse.from(saved);
    }

    /**
     * 단일 페이지 복제 — Confluence "페이지 복사"의 1차 슬라이스.
     *
     * 범위(갭 분석 §4.1 복제 정책의 v1 확정):
     * - 하위 페이지·댓글은 복사하지 않는다(하위 포함 복제는 후속).
     * - CONFIRMED 첨부는 객체까지 복사하고 본문 inline 참조를 사본 첨부로 재작성한다 —
     *   원본을 참조하게 두면 원본 페이지 삭제가 사본 이미지를 깨뜨린다.
     * - PENDING 첨부(다른 편집 세션의 미확정 업로드)는 대상이 아니다.
     * - 제목은 "제목 (사본)", 부모·타입·초안/게시 상태는 원본 그대로.
     */
    public PageResponse copy(long userId, long pageId) {
        Page source = getOwned(pageId);
        spaces.require(userId, source.getSpaceId(), WikiAction.EDIT);
        String suffix = " (사본)";
        String baseTitle = source.getTitle().length() + suffix.length() > 255
                ? source.getTitle().substring(0, 255 - suffix.length())
                : source.getTitle();
        Page saved = pages.save(Page.of(source.getSpaceId(), source.getParentId(),
                baseTitle + suffix, source.getContent(), userId, source.getType(), source.getStatus()));
        saved.resequence(pages.findMaxSortOrder(source.getSpaceId(), source.getParentId()) + 1);

        String content = source.getContent();
        for (Attachment original : attachments.findByPageId(pageId)) {
            if (original.getLifecycleStatus() != AttachmentLifecycleStatus.CONFIRMED) continue;
            Attachment copied = copyAttachment(userId, saved.getId(), original);
            content = content.replace(
                    AttachmentReferences.inlineUrl(original.getId()),
                    AttachmentReferences.inlineUrl(copied.getId()));
        }
        if (!content.equals(source.getContent())) {
            saved.rewriteContentForCopy(content);
        }
        revisions.save(PageRevision.snapshotOf(saved));
        events.afterCommit(WikiEvents.pageCreated(userId, saved));
        return PageResponse.from(saved);
    }

    private Attachment copyAttachment(long userId, long targetPageId, Attachment original) {
        try (var input = storage.open(original.getStorageBackend(), original.getStorageBucket(),
                original.getStorageKey(), original.getStorageVersion()).getInputStream()) {
            StoredObject stored = storage.store(input, original.getSizeBytes(), original.getContentType());
            // DB 트랜잭션이 실패하면 방금 만든 객체를 치운다 — 업로드 경로와 같은 보상 규칙
            storage.deleteAfterRollback(stored);
            return attachments.save(Attachment.of(targetPageId, original.getFilename(),
                    original.getContentType(), original.getSizeBytes(), stored,
                    original.getChecksumSha256(), userId));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("첨부 복사 중 저장소 오류가 발생했습니다", e);
        }
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
    /**
     * 트리 이동/재정렬(P1-001) — 부모 변경과 형제 순서를 한 트랜잭션에서 처리한다.
     * 내용 편집이 아니므로 version을 올리지 않고 리비전도 쌓지 않는다(스토어 계약).
     * 스페이스 행을 잠가 같은 스페이스의 동시 재정렬과 직렬화한다.
     */
    public PageResponse move(long userId, long pageId, PageMoveRequest req) {
        Page page = getOwned(pageId);
        spaces.require(userId, page.getSpaceId(), WikiAction.EDIT);
        spaces.lockForReorder(page.getSpaceId());
        Page locked = pages.findByIdForUpdate(pageId)
                .orElseThrow(() -> new NotFoundException("페이지 없음: " + pageId));
        if (!Objects.equals(locked.getParentId(), req.parentId())) {
            validateParent(locked.getSpaceId(), req.parentId(), pageId);
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
            resequenceSiblings(locked.getSpaceId(), previousParentId, pageId);
            // 부모가 바뀐 사실이 나가지 않으면 색인·활동피드가 스테일해진다(순수 재정렬은 색인 무관)
            events.afterCommit(WikiEvents.pageUpdated(userId, locked));
        }
        return PageResponse.from(locked);
    }

    /** 떠난 그룹을 1..n으로 조밀화 — 빈 번호를 남기지 않아야 이후 삽입 계산이 단순하다. */
    private void resequenceSiblings(Long spaceId, Long parentId, long excludedPageId) {
        List<Page> siblings = pages.findSiblings(spaceId, parentId).stream()
                .filter(p -> p.getId() != excludedPageId)
                .toList();
        for (int i = 0; i < siblings.size(); i++) siblings.get(i).resequence(i + 1L);
    }

    public PageResponse update(long userId, long pageId, PageUpdateRequest req) {
        Page p = getOwned(pageId);
        spaces.require(userId, p.getSpaceId(), WikiAction.EDIT);
        if (!Objects.equals(p.getVersion(), req.expectedVersion())) {
            throw new ConflictException("버전 충돌 — 현재 " + p.getVersion() + ", 요청 " + req.expectedVersion());
        }
        if (!Objects.equals(p.getParentId(), req.parentId())) {
            validateParent(p.getSpaceId(), req.parentId(), pageId);
            Long previousParentId = p.getParentId();
            p.moveTo(req.parentId());
            // 그룹이 바뀌면 새 그룹 맨 뒤로, 떠난 그룹은 조밀하게(V9) — 정밀 배치는 move가 한다
            spaces.lockForReorder(p.getSpaceId());
            p.resequence(pages.findMaxSortOrder(p.getSpaceId(), req.parentId()) + 1);
            resequenceSiblings(p.getSpaceId(), previousParentId, pageId);
        }
        p.edit(req.title(), req.content(), userId);
        revisions.save(PageRevision.snapshotOf(p));
        events.afterCommit(WikiEvents.pageUpdated(userId, p));
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
        CollaborationDraftMetadata draft = collaborationDrafts
                .findByRoomForUpdate(CollaborationDraftMetadata.room(pageId))
                .orElseThrow(() -> new ConflictException("공동 초안이 준비되지 않았습니다"));

        if (!Objects.equals(page.getVersion(), req.expectedPageVersion())
                || !Objects.equals(draft.getBasePageVersion(), req.expectedPageVersion().longValue())
                || !Objects.equals(draft.getGeneration(), req.expectedGeneration())) {
            throw new ConflictException("공동 초안 버전이 변경되었습니다. 동기화 후 다시 저장하세요");
        }

        page.edit(req.title(), req.content(), userId);
        draft.advanceTo(page.getVersion());
        revisions.save(PageRevision.snapshotOf(page));
        events.afterCommit(WikiEvents.pageUpdated(userId, page));
        return new CollaborationDraftCommitResponse(PageResponse.from(page), draft.getGeneration());
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
                long nextOrder = pages.findMaxSortOrder(p.getSpaceId(), p.getParentId());
                for (Page child : children) {
                    child.rankTo(p.getParentId(), ++nextOrder);
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
            storage.deleteAfterCommit(a.getStorageBackend(), a.getStorageBucket(),
                    a.getStorageKey(), a.getStorageVersion());
        });
        attachments.deleteByPageId(pageId);

        // 리비전·댓글 명시 삭제 — H2 테스트 스키마는 FK 없음(Long 컬럼만) → 고아 방지
        revisions.deleteByPageId(pageId);
        comments.deleteByPageId(pageId);
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
