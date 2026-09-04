package com.platform.wikibackend.migration.confluence;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageLabel;
import com.platform.wikibackend.domain.PageRevision;
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

        Page page = pages.save(Page.imported(source.spaceId(), source.parentId(), title,
                source.markdown(), source.authorId(), source.createdAt(), source.updatedAt()));
        page.resequence(pages.findMaxSortOrder(source.spaceId(), source.parentId()) + 1);
        pages.flush();

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
     * 리비전에는 원본 작성자의 **표시 이름**을 남긴다(V28의 편집자 이름 스냅샷). 우리 계정으로
     * 대조되지 않은 사람도 이력에서는 자기 이름으로 남는다 — id로만 남기면 전부 이관 담당자가 쓴
     * 것처럼 보인다.
     */
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
                               Instant createdAt, Instant updatedAt, List<String> labels) {

        public ImportedPage {
            labels = labels == null ? List.of() : List.copyOf(labels);
        }
    }

    public record ImportResult(long pageId, List<MigrationStageIssue> issues) {
    }
}
