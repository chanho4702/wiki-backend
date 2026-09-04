package com.platform.wikibackend.migration.confluence;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageLabel;
import com.platform.wikibackend.domain.PageRevision;
import com.platform.wikibackend.domain.PageType;
import com.platform.wikibackend.event.EventRelay;
import com.platform.wikibackend.event.WikiEvents;
import com.platform.wikibackend.label.LabelService;
import com.platform.wikibackend.migration.worker.MigrationStageIssue;
import com.platform.wikibackend.repository.PageLabelRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.task.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * IR에서 뽑은 마크다운을 실제 위키 문서로 쓴다.
 *
 * 일반 생성 경로(PageService.create)를 부르지 않는 이유는 세 가지다.
 * 1. **알림을 쏘지 않는다** — 500페이지를 옮기면 스페이스 구독자 전원에게 500통이 간다. 이관은
 *    "새 글이 올라왔다"는 사건이 아니라 데이터 이전이다.
 * 2. **자동 구독하지 않는다** — 이관 담당자가 옮긴 문서 전부를 구독하게 되는 건 사고에 가깝다.
 * 3. **시각을 원본 것으로 되돌린다** — 저장 뒤 created_at/updated_at을 한 번 더 눌러야 한다.
 *
 * 반대로 검색 색인 이벤트(pageCreated/pageUpdated)는 **발행한다**. 이 플랫폼에서 색인은 이 이벤트
 * 하나로만 갱신되므로(EventRelay 주석), 빼면 옮긴 문서가 검색에 영영 안 잡힌다. 알림과 색인이
 * 같은 이벤트를 타지 않는다 — 알림은 NotificationService가 따로 만든다.
 *
 * worker의 트랜잭션 밖에서 불리므로 스스로 트랜잭션을 연다.
 */
@Component
@RequiredArgsConstructor
public class ImportedPageWriter {

    /** 원본 제목이 우리 상한을 넘어 잘랐다. */
    public static final String TITLE_TRUNCATED = "TITLE_TRUNCATED";

    /** page.title은 varchar(255)다 — 넘치면 저장 자체가 실패하므로 잘라서라도 옮긴다. */
    public static final int MAX_TITLE_LENGTH = 255;

    private final PageRepository pages;
    private final PageRevisionRepository revisions;
    private final PageLabelRepository labels;
    private final LabelService labelService;
    private final TaskService tasks;
    private final EventRelay events;

    /**
     * 새 문서를 만든다.
     *
     * @return 만들어진 페이지 id와 그 과정의 손실
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImportResult create(ImportedPage source) {
        List<MigrationStageIssue> issues = new ArrayList<>();
        String title = truncateTitle(source.title(), source.externalObjectId(), issues);

        // 지난 버전을 함께 옮기면 문서는 k+1번째 버전으로 태어난다(M3 §5.3) — 리비전 1..k가
        // 원본 이력이고 k+1이 현재본이다. 이력은 **최초 이관에만** 쌓는다: 재이관에서 다시 깔면
        // 그 사이 사람이 손댄 리비전과 번호가 엉킨다.
        List<ImportedRevision> history = source.history();
        Page page = pages.save(Page.imported(source.spaceId(), source.parentId(), title,
                source.markdown(), source.authorId(), source.createdAt(), source.updatedAt(),
                source.type(), history.size() + 1));
        applyImportedAuthor(page, source);
        page.resequence(sortOrderOf(source));
        pages.flush();

        writeHistory(page, source, title);
        writeRevision(page, source, null);
        replaceLabels(page.getId(), source.labels(), source.authorId());
        tasks.sync(page);
        labelService.reindexLinks(page);
        pages.overwriteTimestamps(page.getId(), source.createdAt(), source.updatedAt());
        events.afterCommit(WikiEvents.pageCreated(source.authorId(), page));
        return new ImportResult(page.getId(), issues);
    }

    /**
     * 이미 옮긴 문서를 원본의 새 버전으로 갱신한다. 새 리비전이 쌓이므로 이관 전 손댄 내용이
     * 사라지지 않고 이력에 남는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImportResult update(long pageId, ImportedPage source, String changeNote) {
        List<MigrationStageIssue> issues = new ArrayList<>();
        String title = truncateTitle(source.title(), source.externalObjectId(), issues);
        Page page = pages.findById(pageId).orElseThrow();

        page.reimport(title, source.markdown(), source.authorId(), source.updatedAt());
        applyImportedAuthor(page, source);
        if (source.siblingOrder() != null) {
            // 원본에서 순서만 바뀐 경우다. movePage가 아니라 sortOrder만 눌러 준다 —
            // 이동 경로는 부모 재계산·권한 검사까지 도는데 여기서는 부모가 그대로다.
            page.resequence(source.siblingOrder());
        }
        pages.flush();

        writeRevision(page, source, changeNote);
        replaceLabels(page.getId(), source.labels(), source.authorId());
        tasks.sync(page);
        labelService.reindexLinks(page);
        pages.overwriteTimestamps(page.getId(), page.getCreatedAt(), source.updatedAt());
        events.afterCommit(WikiEvents.pageUpdated(source.authorId(), page));
        return new ImportResult(page.getId(), issues);
    }

    /**
     * 첨부를 등록한 뒤 본문의 참조만 실제 URL로 바꿔 넣는다(M2 §4.1).
     *
     * 새 리비전을 만들지 않는다. 첨부 참조 정리는 이관이라는 한 번의 저장을 끝맺는 일이지 별도의
     * 편집이 아니고, 리비전을 하나 더 쌓으면 옮겨온 문서마다 "v2 수정됨"이 생겨 이력이 거짓이 된다.
     * 대신 방금 쓴 리비전의 본문도 같이 눌러 현재와 이력을 일치시킨다.
     *
     * 검색 색인은 다시 쏜다 — 본문이 바뀌었으므로 색인도 바뀌어야 한다.
     *
     * @return 실제로 바뀌었으면 true
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean rewriteBody(long pageId, String markdown) {
        Page page = pages.findById(pageId).orElseThrow();
        if (page.getContent().equals(markdown)) {
            return false;
        }
        page.rewriteImportedContent(markdown);
        pages.flush();
        revisions.findByPageIdAndVersion(pageId, page.getVersion())
                .ifPresent(revision -> {
                    revision.replaceContent(markdown);
                    revisions.save(revision);
                });
        tasks.sync(page);
        labelService.reindexLinks(page);
        events.afterCommit(WikiEvents.pageUpdated(page.getUpdatedBy(), page));
        return true;
    }

    /**
     * 순번만 갱신한다(M2 §4.4). 원본에서 문서 순서만 바뀐 재이관이 여기로 온다 — 본문이 그대로라
     * 리비전도 이벤트도 만들 이유가 없다.
     *
     * @return 실제로 바뀌었으면 true
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean resequence(long pageId, Integer siblingOrder) {
        if (siblingOrder == null) {
            return false;
        }
        Page page = pages.findById(pageId).orElse(null);
        if (page == null || page.getSortOrder() == siblingOrder.longValue()) {
            return false;
        }
        page.resequence(siblingOrder);
        pages.flush();
        return true;
    }

    /**
     * 정렬 순번. 원본이 알려준 형제 순서가 있으면 그대로 쓰고(M2 §4.4), 없으면 발견 순서대로
     * 뒤에 붙인다(M1 규칙).
     */
    private long sortOrderOf(ImportedPage source) {
        if (source.siblingOrder() != null) {
            return source.siblingOrder();
        }
        return pages.findMaxSortOrder(source.spaceId(), source.parentId()) + 1;
    }

    /**
     * 리비전에는 원본 작성자의 **표시 이름**을 남긴다(V28의 편집자 이름 스냅샷). 우리 계정으로
     * 대조되지 않은 사람도 이력에서는 자기 이름으로 남는다 — id로만 남기면 전부 이관 담당자가 쓴
     * 것처럼 보인다.
     */
    /**
     * 원본 작성자를 우리 계정으로 **대조하지 못했을 때만** 이름·원본 주소를 남긴다(M3 §5.4).
     * 대조에 성공한 문서는 두 값을 비워 화면이 평소대로 우리 사용자를 보여주게 한다 — 한 번
     * 채워 둔 값이 남아 있으면 나중에 대조가 되어도 계속 "이관됨"으로 보인다.
     */
    private void applyImportedAuthor(Page page, ImportedPage source) {
        if (source.authorMapped()) {
            page.markImportedAuthor(null, null);
            return;
        }
        page.markImportedAuthor(source.authorDisplayName(), source.sourceUrl());
    }

    /**
     * 원본의 지난 버전을 리비전 1..k로 깐다. 오래된 것부터라 이력을 위에서 아래로 읽으면 원본과
     * 같은 순서가 된다. 편집자는 이름 스냅샷(V28)으로만 남는다 — 계정을 새로 만들지 않는다.
     */
    private void writeHistory(Page page, ImportedPage source, String currentTitle) {
        int version = 1;
        for (ImportedRevision revision : source.history()) {
            String title = revision.title() == null || revision.title().isBlank()
                    ? currentTitle
                    : truncateTitle(revision.title(), source.externalObjectId(), new ArrayList<>());
            PageRevision row = revisions.save(PageRevision.imported(page.getId(), version++, title,
                    revision.markdown(), source.authorId(), revision.editorName(),
                    revision.changeNote()));
            revisions.flush();
            if (revision.savedAt() != null) {
                revisions.overwriteCreatedAt(row.getId(), revision.savedAt());
            }
        }
    }

    private void writeRevision(Page page, ImportedPage source, String changeNote) {
        PageRevision revision = PageRevision.snapshotOf(page, changeNote)
                .withEditorName(source.authorDisplayName());
        revisions.save(revision);
        revisions.flush();
        revisions.overwriteCreatedAt(revision.getId(), source.updatedAt());
    }

    /**
     * 라벨은 LabelService.replace가 아니라 여기서 직접 넣는다. 그 경로는 권한 검사와 함께
     * pageUpdated 이벤트를 한 번 더 쏘는데, 문서 저장에서 이미 쏘고 있어 페이지마다 색인이
     * 두 번 돈다.
     */
    private void replaceLabels(long pageId, List<String> raw, long authorId) {
        labels.deleteByPageId(pageId);
        labels.flush();
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : raw) {
            try {
                normalized.add(PageLabel.normalize(value));
            } catch (IllegalArgumentException ignored) {
                // 빈 라벨·상한 초과는 원본 쪽 사정이다. 라벨 하나 때문에 문서 이관을 실패시키지 않는다.
            }
        }
        List<PageLabel> rows = normalized.stream()
                .map(name -> PageLabel.of(pageId, name, authorId))
                .toList();
        labels.saveAll(rows);
    }

    private String truncateTitle(String title, String externalObjectId, List<MigrationStageIssue> issues) {
        String value = title == null || title.isBlank() ? "제목 없음" : title.trim();
        if (value.length() <= MAX_TITLE_LENGTH) {
            return value;
        }
        issues.add(MigrationStageIssue.warning(TITLE_TRUNCATED, "page:" + externalObjectId));
        return value.substring(0, MAX_TITLE_LENGTH);
    }

    /**
     * 한 문서를 쓰는 데 필요한 값. authorId는 이미 우리 사용자로 결정된 값이고(대조 실패 시
     * 잡 요청자), authorDisplayName은 원본에 적혀 있던 이름 그대로다.
     */
    public record ImportedPage(long spaceId, Long parentId, String externalObjectId, String title,
                               String markdown, long authorId, String authorDisplayName,
                               /** 원본 작성자를 우리 계정으로 찾았는가. 못 찾았으면 이름·원본 주소를 문서에 남긴다(M3). */
                               boolean authorMapped,
                               String sourceUrl,
                               Instant createdAt, Instant updatedAt, List<String> labels,
                               Integer siblingOrder,
                               /** 원본이 블로그 글이면 BLOG(M3 §5.1). 트리에 넣으면 날짜순 글이 폴더 밑에 박힌다. */
                               PageType type,
                               /** 함께 옮길 지난 버전. 오래된 것부터다. 비어 있으면 현재본만 남는다. */
                               List<ImportedRevision> history) {

        public ImportedPage {
            labels = labels == null ? List.of() : List.copyOf(labels);
            history = history == null ? List.of() : List.copyOf(history);
            type = type == null ? PageType.PAGE : type;
        }
    }

    /** 원본의 지난 버전 하나. 리비전 번호는 writer가 1부터 다시 매긴다. */
    public record ImportedRevision(String title, String markdown, String editorName, String changeNote,
                                   Instant savedAt) {
    }

    public record ImportResult(long pageId, List<MigrationStageIssue> issues) {
    }
}
