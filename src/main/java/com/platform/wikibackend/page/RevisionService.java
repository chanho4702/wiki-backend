package com.platform.wikibackend.page;

import com.platform.common.error.NotFoundException;
import com.platform.wikibackend.collaboration.CollaborationDraftReset;
import com.platform.wikibackend.common.ActorNames;
import com.platform.wikibackend.directory.DisplayNames;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRevision;
import com.platform.wikibackend.event.EventRelay;
import com.platform.wikibackend.event.WikiEvents;
import com.platform.wikibackend.label.LabelService;
import com.platform.wikibackend.page.dto.PageResponse;
import com.platform.wikibackend.page.dto.RevisionMeta;
import com.platform.wikibackend.page.dto.RevisionPage;
import com.platform.wikibackend.page.dto.RevisionResponse;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.space.SpaceService;
import com.platform.wikibackend.task.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 이력(리비전) 조회와 복원. 복원은 새 버전을 만드는 쓰기다. PageService에서 분리(2026-10-18). */
@Service
@RequiredArgsConstructor
@Transactional
public class RevisionService {

    private final PageRevisionRepository revisions;
    private final SpaceService spaces;
    private final EffectivePermissionService effective;
    private final EventRelay events;
    private final LabelService labelService;
    private final ActorNames actorNames;
    private final TaskService taskSync;
    private final DisplayNames displayNames;
    private final CollaborationDraftReset collaborationReset;
    private final PageStructure structure;

    /**
     * 이력 전체 목록(메타만). 화면은 쪽 단위({@link #listRevisionPage})를 쓴다.
     */
    @Transactional(readOnly = true)
    public List<RevisionMeta> listRevisions(long userId, long pageId) {
        requireRevisionView(userId, pageId);
        // 전량 계약(기존 호출자) — 그래도 본문 바이트는 읽지 않는다(메타 프로젝션).
        return withEditorNames(revisions.findMetaByPageId(pageId));
    }

    /** 한 쪽 상한 — 화면의 "더 보기" 한 번이 읽는 양. */
    public static final int REVISION_PAGE_MAX = 100;

    /**
     * 이력 한 쪽(최신순, 버전 번호 커서). 버전이 수백 개인 문서도 첫 화면은 한 쪽만 읽는다.
     * 전량 목록({@link #listRevisions})은 그대로 둔다 — 그걸 부르는 기존 호출자가 조용히 잘린
     * 목록을 받으면 안 된다.
     */
    @Transactional(readOnly = true)
    public RevisionPage listRevisionPage(
            long userId, long pageId, Integer before, Integer limit) {
        requireRevisionView(userId, pageId);
        int size = limit == null ? 50 : Math.max(1, Math.min(limit, REVISION_PAGE_MAX));
        // 한 건 더 읽어 다음 쪽이 있는지 안다 — 별도 존재 질의를 두지 않는다.
        List<RevisionMeta> rows = revisions.findMetaPage(pageId, before,
                Limit.of(size + 1));
        boolean more = rows.size() > size;
        List<RevisionMeta> items = more ? rows.subList(0, size) : rows;
        Integer next = more ? items.get(items.size() - 1).version() : null;
        return new RevisionPage(
                withEditorNames(items), next, revisions.countByPageId(pageId));
    }

    private void requireRevisionView(long userId, long pageId) {
        Page p = structure.getOwned(pageId);
        spaces.require(userId, p.getSpaceId(), WikiAction.VIEW);
        effective.requireView(userId, p);
    }

    /**
     * 편집자 이름은 저장 시점 스냅샷(V28)이고, 그 값이 없는 옛 리비전만 org 원장에서 채운다 —
     * 목록 하나에 조회 <b>한 번</b>이다. 못 채우면 이름 없이 나가고 화면이 id로 폴백한다.
     */
    private List<RevisionMeta> withEditorNames(List<RevisionMeta> rows) {
        Map<Long, String> names = displayNames.resolve(rows.stream()
                .filter(r -> DisplayNames.needsFill(
                        r.editedByName(), r.editedBy() == null ? 0L : r.editedBy()))
                .map(RevisionMeta::editedBy)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        return rows.stream()
                .map(r -> new RevisionMeta(r.version(), r.editedBy(), r.createdAt(), r.changeNote(),
                        filled(r.editedByName(), r.editedBy(), names)))
                .toList();
    }

    @Transactional(readOnly = true)
    public RevisionResponse getRevision(long userId, long pageId, int version) {
        Page p = structure.getOwned(pageId);
        spaces.require(userId, p.getSpaceId(), WikiAction.VIEW);
        effective.requireView(userId, p);
        PageRevision revision = revisions.findByPageIdAndVersion(pageId, version)
                .orElseThrow(() -> new NotFoundException("리비전 없음: v" + version));
        Long editor = revision.getEditedBy();
        // 이름이 이미 있으면 원장을 부르지 않는다 — 대부분의 리비전이 여기서 끝난다
        Map<Long, String> names =
                editor != null && DisplayNames.needsFill(revision.getEditedByName(), editor)
                        ? displayNames.resolve(List.of(editor))
                        : Map.of();
        return RevisionResponse.from(revision, filled(revision.getEditedByName(), editor, names));
    }

    /**
     * 스냅샷이 있으면 그대로 — 그때 그 사람이 쓰던 이름이라 지금 이름으로 덮지 않는다.
     * 비어 있을 때만 원장의 이름을 넣고, 그것도 없으면 null로 두어 화면이 id로 폴백하게 한다.
     */
    private static String filled(String stored, Long userId, Map<Long, String> names) {
        if (userId == null) return stored;
        if (!DisplayNames.needsFill(stored, userId)) return stored;
        return names.getOrDefault(userId, stored);
    }

    /** 복원 = 해당 리비전 내용으로 새 버전 생성(이력 보존 — 스펙). */
    public PageResponse restore(long userId, long pageId, int version) {
        return restore(userId, pageId, version, null);
    }

    /**
     * 사람이 남긴 요약과 함께 복원한다(W30).
     *
     * 요약이 비어 있으면 기본값("v{n} 버전으로 복원")을 쓴다 — 어느 버전에서 되돌렸는지는 사람이
     * 무엇을 적었든 이력에 남아야 하는 정보다. 사람이 적었으면 그것이 이긴다: 왜 되돌렸는지는
     * 서버가 알 수 없고, 그 문장이 다음 사람에게 더 쓸모 있다.
     */
    public PageResponse restore(long userId, long pageId, int version, String changeNote) {
        Page p = structure.lockOwned(pageId); // page → 공동 초안 잠금 순서(update와 같다)
        spaces.require(userId, p.getSpaceId(), WikiAction.EDIT);
        effective.requireEdit(userId, p);
        PageWriteGuard.requireWritable(p); // 이력 화면의 "이 버전으로 복원"도 편집이다
        PageRevision target = revisions.findByPageIdAndVersion(pageId, version)
                .orElseThrow(() -> new NotFoundException("리비전 없음: v" + version));
        p.edit(target.getTitle(), target.getContent(), userId);
        collaborationReset.afterExternalWrite(p); // 복원도 새 버전이다 — 공동 초안을 새 기준으로(차단-1)
        // 복원도 이력에 남는다 — 어느 버전에서 되돌렸는지가 다음 사람에게 가장 중요한 정보다.
        String note = changeNote == null || changeNote.isBlank()
                ? "v" + version + " 버전으로 복원"
                : changeNote;
        revisions.save(PageRevision.snapshotOf(p, note).withEditorName(actorNames.current()));
        taskSync.sync(p); // 작업 표는 본문의 파생물(W23) — 리비전과 같은 자리에서 갱신
        labelService.reindexLinks(p);
        events.afterCommit(WikiEvents.pageUpdated(userId, p));
        return PageResponse.from(p);
    }
}
