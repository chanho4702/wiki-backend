package com.platform.wikibackend.migration.confluence.link;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.migration.confluence.ImportedPageWriter;
import com.platform.wikibackend.migration.model.MigrationIssue;
import com.platform.wikibackend.migration.model.MigrationItem;
import com.platform.wikibackend.migration.model.MigrationJob;
import com.platform.wikibackend.migration.model.MigrationObjectMapping;
import com.platform.wikibackend.migration.repository.MigrationIssueRepository;
import com.platform.wikibackend.migration.repository.MigrationItemRepository;
import com.platform.wikibackend.migration.worker.MigrationStageIssue;
import com.platform.wikibackend.repository.PageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 링크 정리 pass의 문서 한 건. 별도 빈으로 둔 이유는 트랜잭션 때문이다 — 같은 클래스 안에서
 * 부르면 프록시를 타지 않아 REQUIRES_NEW가 무시되고, 한 문서의 실패가 나머지 정리를 통째로 무른다.
 */
@Component
@RequiredArgsConstructor
public class MigrationLinkFixupWriter {

    /** `[[제목]]` — writer가 이스케이프 없이 그대로 쓰는 형태다. */
    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([^\\]\\n]+)\\]\\]");

    /** 링크 정리 리비전에 붙는 변경 요약. 화면에서 이 문구로 이관 정리를 알아본다. */
    public static final String CHANGE_NOTE = "이관 링크 정리";

    private final MigrationLinkRewriter rewriter;
    private final MigrationItemRepository items;
    private final MigrationIssueRepository issues;
    private final PageRepository pages;
    private final ImportedPageWriter writer;

    /** @return 본문이 실제로 바뀌었으면 true */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fixOne(MigrationJob job, MigrationObjectMapping mapping,
                          MigrationLinkResolver.Context context) {
        Page page = pages.findById(mapping.getTargetPageId()).orElse(null);
        if (page == null) {
            return false;
        }
        String before = page.getContent();
        recordIssues(job, mapping, ambiguousWikiLinks(before, context.targetSpaceId()));
        if (!before.contains(DcPageReference.TEMP_SCHEME)) {
            // 임시 링크가 없는 문서는 더 볼 것이 없다. 대부분의 문서가 여기서 걸러진다.
            return false;
        }
        MigrationLinkRewriter.Result result = rewriter.rewriteTempLinks(before, context);
        recordIssues(job, mapping, result.issues());
        if (result.markdown().equals(before)) {
            return false;
        }
        // 실제 쓰기는 ImportedPageWriter가 한다 — import API의 bumpVersion 경로와 같은 코드를 타야
        // 두 입구가 조용히 갈라지지 않는다.
        return writer.applyRevision(page, result.markdown(), job.getRequestedBy(), CHANGE_NOTE);
    }

    /**
     * 제목 기반 위키링크(`[[제목]]`)가 대상 스페이스에서 여럿에 걸리는지 본다.
     *
     * 이 링크는 저장 시점이 아니라 **보는 시점에** 제목으로 해석된다. 그래서 우리가 고쳐 줄 것은
     * 없지만, 어느 문서로 열릴지 모른다는 사실은 보고서에 남아야 한다 — 이관 뒤 "링크가 엉뚱한
     * 문서로 간다"를 원인 없이 만나는 것이 가장 나쁘다.
     */
    private List<MigrationStageIssue> ambiguousWikiLinks(String markdown, long targetSpaceId) {
        Matcher matcher = WIKI_LINK.matcher(markdown);
        Set<String> seen = new LinkedHashSet<>();
        List<MigrationStageIssue> found = new ArrayList<>();
        while (matcher.find()) {
            String title = matcher.group(1).trim();
            if (title.isEmpty() || !seen.add(title)) {
                continue;
            }
            if (pages.findBySpaceIdAndTitleIgnoringCase(targetSpaceId, title).size() > 1) {
                found.add(MigrationStageIssue.warning(
                        com.platform.wikibackend.migration.confluence.handler.ConfluenceDcIssues.LINK_AMBIGUOUS,
                        "link:" + title));
            }
        }
        return found;
    }

    /** 손실은 그 문서를 만든 항목에 붙인다 — 보고서가 "어느 원본에서 난 문제인지"를 잃지 않는다. */
    private void recordIssues(MigrationJob job, MigrationObjectMapping mapping,
                              List<MigrationStageIssue> reported) {
        if (reported.isEmpty()) {
            return;
        }
        Optional<MigrationItem> item = items.findByJobIdAndSourceKey(job.getId(),
                MigrationItem.sourceKeyFor(mapping.getExternalObjectId()));
        if (item.isEmpty()) {
            return;
        }
        for (MigrationStageIssue issue : reported) {
            String issueKey = MigrationIssue.issueKeyFor(issue.code(), issue.sourcePath());
            Optional<MigrationIssue> existing =
                    issues.findByItemIdAndIssueKey(item.get().getId(), issueKey);
            if (existing.isPresent()) {
                existing.get().incrementOccurrence();
                issues.save(existing.get());
                continue;
            }
            issues.save(MigrationIssue.of(job.getId(), item.get().getId(), issue.severity(),
                    issue.code(), issue.sourcePath()));
        }
    }
}
