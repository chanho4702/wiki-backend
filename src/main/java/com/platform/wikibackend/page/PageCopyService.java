package com.platform.wikibackend.page;

import com.platform.wikibackend.attachment.AttachmentLifecycleStatus;
import com.platform.wikibackend.attachment.AttachmentReferences;
import com.platform.wikibackend.attachment.AttachmentStorageRouter;
import com.platform.wikibackend.attachment.StoredObject;
import com.platform.wikibackend.common.ActorNames;
import com.platform.wikibackend.domain.Attachment;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRestriction;
import com.platform.wikibackend.domain.PageRevision;
import com.platform.wikibackend.event.EventRelay;
import com.platform.wikibackend.event.WikiEvents;
import com.platform.wikibackend.label.LabelService;
import com.platform.wikibackend.page.dto.CopyRequest;
import com.platform.wikibackend.page.dto.PageResponse;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRestrictionRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.space.SpaceService;
import com.platform.wikibackend.task.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 페이지 복사(단건·하위 포함) — 첨부 객체·제한까지 옮기는 쓰기 경로. PageService에서 분리(2026-10-18). */
@Service
@RequiredArgsConstructor
@Transactional
public class PageCopyService {

    private final PageRepository pages;
    private final PageRevisionRepository revisions;
    private final PageRestrictionRepository restrictions;
    private final SpaceService spaces;
    private final EffectivePermissionService effective;
    private final EventRelay events;
    private final AttachmentRepository attachments;
    private final AttachmentStorageRouter storage;
    private final LabelService labelService;
    private final ActorNames actorNames;
    private final TaskService taskSync;
    private final PageStructure structure;

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
    /**
     * 한 번에 복사할 수 있는 페이지 수 상한.
     *
     * 서브트리 복사는 페이지마다 첨부를 저장소에서 읽어 다시 쓴다 — 한 트랜잭션에서 무한정
     * 돌릴 일이 아니다. 넘으면 거절해서, 절반만 복사된 트리를 남기지 않는다.
     */
    public static final int MAX_COPY_PAGES = 200;

    public PageResponse copy(long userId, long pageId, CopyRequest req) {
        Page source = structure.getOwned(pageId);
        spaces.require(userId, source.getSpaceId(), WikiAction.EDIT);
        effective.requireEdit(userId, source);
        // 사본은 원본의 부모 아래 생긴다 — 그 부모가 보관 중이면 보관 상자에 새 문서가 들어간다
        if (source.getParentId() != null) pages.findById(source.getParentId()).ifPresent(PageWriteGuard::requireWritableParent);

        List<Page> sources = new ArrayList<>(List.of(source));
        if (req.descendantsIncluded()) sources.addAll(copyableDescendants(userId, source));
        if (sources.size() > MAX_COPY_PAGES) {
            throw new IllegalArgumentException(
                    "한 번에 복사할 수 있는 문서는 " + MAX_COPY_PAGES + "개까지입니다 (요청 "
                            + sources.size() + "개)");
        }

        long rootSortOrder = pages.findMaxSortOrder(source.getSpaceId(), source.getParentId()) + 1;
        Map<Long, Long> newIdOf = new HashMap<>();
        Page copiedRoot = null;
        for (Page original : sources) {
            boolean isRoot = original.getId().equals(source.getId());
            // 사본 표시는 뿌리에만 붙인다 — 하위까지 제목을 바꾸면 문서 안의 `[[제목]]`이 전부 어긋난다.
            String title = isRoot ? copyTitle(original.getTitle()) : original.getTitle();
            Long parentId = isRoot ? original.getParentId() : newIdOf.get(original.getParentId());
            Page saved = pages.save(Page.of(original.getSpaceId(), parentId, title,
                    original.getContent(), userId, original.getType(), original.getStatus()));
            saved.resequence(isRoot ? rootSortOrder : original.getSortOrder());
            newIdOf.put(original.getId(), saved.getId());
            if (isRoot) copiedRoot = saved;

            copyAttachmentsInto(userId, original, saved);
            if (req.restrictionsIncluded()) copyRestrictionsInto(userId, original, saved);
            revisions.save(PageRevision.snapshotOf(saved).withEditorName(actorNames.current()));
            taskSync.sync(saved); // 작업 표는 본문의 파생물(W23) — 리비전과 같은 자리에서 갱신
            labelService.reindexLinks(saved);
            events.afterCommit(WikiEvents.pageCreated(userId, saved));
        }
        return PageResponse.from(Objects.requireNonNull(copiedRoot));
    }

    /**
     * 복사 대상 후손 — **부모가 먼저 오는 순서**로 준다(자식이 부모의 새 id를 필요로 한다).
     *
     * 볼 수 없는 문서는 넣지 않는다. VIEW 제한은 하위로 상속되므로 가려진 노드를 빼면 그 아래도
     * 자연히 빠진다 — 사용자는 애초에 그 문서의 존재를 모르며, 몰래 복사해 열어 두는 쪽이 나쁘다.
     */
    private List<Page> copyableDescendants(long userId, Page root) {
        List<Long> ids = pages.findDescendantIds(root.getId());
        if (ids.isEmpty()) return List.of();
        List<Page> found = pages.findAllById(ids);
        Set<Long> visible = effective.viewablePageIds(userId, found);

        Map<Long, List<Page>> childrenOf = new HashMap<>();
        for (Page p : found) {
            if (!visible.contains(p.getId())) continue;
            childrenOf.computeIfAbsent(p.getParentId(), k -> new ArrayList<>()).add(p);
        }
        for (List<Page> siblings : childrenOf.values()) {
            siblings.sort(Comparator.comparing(Page::getSortOrder).thenComparing(Page::getId));
        }

        List<Page> ordered = new ArrayList<>();
        Deque<Long> queue = new ArrayDeque<>(List.of(root.getId()));
        Set<Long> seen = new HashSet<>(List.of(root.getId()));
        while (!queue.isEmpty()) {
            for (Page child : childrenOf.getOrDefault(queue.poll(), List.of())) {
                if (!seen.add(child.getId())) continue; // 순환 방어 — 폐포가 이미 막지만 두 겹으로 둔다
                ordered.add(child);
                queue.add(child.getId());
            }
        }
        return ordered;
    }

    private static String copyTitle(String title) {
        String suffix = " (사본)";
        String base = title.length() + suffix.length() > 255
                ? title.substring(0, 255 - suffix.length())
                : title;
        return base + suffix;
    }

    /** 첨부를 복제하고, 본문의 인라인 참조를 새 첨부 id로 바꾼다. */
    private void copyAttachmentsInto(long userId, Page original, Page saved) {
        String content = original.getContent();
        for (Attachment source : attachments.findByPageId(original.getId())) {
            if (source.getLifecycleStatus() != AttachmentLifecycleStatus.CONFIRMED) continue;
            Attachment copied = copyAttachment(userId, saved.getId(), source);
            content = content.replace(
                    AttachmentReferences.inlineUrl(source.getId()),
                    AttachmentReferences.inlineUrl(copied.getId()));
        }
        if (!content.equals(original.getContent())) saved.rewriteContentForCopy(content);
    }

    /** 원본에 직접 걸린 제한만 옮긴다 — 상속분은 사본의 새 조상에서 다시 계산된다. */
    private void copyRestrictionsInto(long userId, Page original, Page saved) {
        for (PageRestriction source : restrictions.findByPageId(original.getId())) {
            restrictions.save(PageRestriction.of(saved.getId(), source.getType(),
                    source.getPrincipalType(), source.getPrincipalId(), userId));
        }
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
        } catch (IOException e) {
            throw new IllegalStateException("첨부 복사 중 저장소 오류가 발생했습니다", e);
        }
    }
}
