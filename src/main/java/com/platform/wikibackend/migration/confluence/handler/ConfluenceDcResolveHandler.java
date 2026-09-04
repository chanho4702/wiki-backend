package com.platform.wikibackend.migration.confluence.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.wikibackend.migration.MigrationPayloadStore;
import com.platform.wikibackend.migration.confluence.ImportedPageWriter;
import com.platform.wikibackend.migration.ir.DocumentIrMarkdownContext;
import com.platform.wikibackend.migration.ir.DocumentIrMarkdownResult;
import com.platform.wikibackend.migration.ir.DocumentIrMarkdownWriter;
import com.platform.wikibackend.migration.model.MigrationJob;
import com.platform.wikibackend.migration.model.MigrationObjectMapping;
import com.platform.wikibackend.migration.model.MigrationPayloadKind;
import com.platform.wikibackend.migration.model.MigrationProvider;
import com.platform.wikibackend.migration.model.MigrationSource;
import com.platform.wikibackend.migration.model.MigrationStage;
import com.platform.wikibackend.migration.repository.MigrationJobRepository;
import com.platform.wikibackend.migration.repository.MigrationObjectMappingRepository;
import com.platform.wikibackend.migration.repository.MigrationSourceRepository;
import com.platform.wikibackend.migration.worker.MigrationObjectMappingWriter;
import com.platform.wikibackend.migration.worker.MigrationStageException;
import com.platform.wikibackend.migration.worker.MigrationStageHandler;
import com.platform.wikibackend.migration.worker.MigrationStageIssue;
import com.platform.wikibackend.migration.worker.MigrationStageOutcome;
import com.platform.wikibackend.migration.worker.MigrationStageWork;
import com.platform.wikibackend.repository.PageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * IR → 마크다운 → (import면) 실제 문서.
 *
 * 재실행 규칙이 여기에 있다. 같은 원본을 같은 상태로 다시 만나면 **아무것도 다시 쓰지 않고** 이미
 * 만든 문서 id를 돌려준다(S1 멱등). 원본이 바뀌었으면 새 리비전으로 갱신한다 — 지우고 다시 만들면
 * 링크·별표·댓글이 딸려 사라진다.
 */
@Component
@RequiredArgsConstructor
public class ConfluenceDcResolveHandler implements MigrationStageHandler {

    private final MigrationPayloadStore payloads;
    private final DocumentIrMarkdownWriter markdownWriter;
    private final ImportedPageWriter pageWriter;
    private final MigrationObjectMappingWriter objectMappings;
    private final MigrationObjectMappingRepository mappings;
    private final MigrationJobRepository jobs;
    private final MigrationSourceRepository sources;
    private final PageRepository pages;
    private final ObjectMapper objectMapper;

    @Override
    public MigrationProvider provider() {
        return MigrationProvider.CONFLUENCE_DC;
    }

    @Override
    public MigrationStage stage() {
        return MigrationStage.RESOLVE;
    }

    @Override
    public MigrationStageOutcome handle(MigrationStageWork work) {
        JsonNode ir = parse(payloads.require(work.itemId(), MigrationPayloadKind.IR).body());
        MigrationSource source = sources.findById(work.jobId()).orElse(null);
        DocumentIrMarkdownContext context = source == null
                ? DocumentIrMarkdownContext.none()
                : new DocumentIrMarkdownContext(source.getSpaceKey(), source.getBaseUrl());

        DocumentIrMarkdownResult rendered = markdownWriter.write(ir, context);
        payloads.write(work.itemId(), MigrationPayloadKind.MARKDOWN, rendered.markdown());
        List<MigrationStageIssue> issues = new ArrayList<>(rendered.issues());

        if (work.dryRun()) {
            // dry-run은 쓰기 0건이 약속이다(M-02). 여기서 멈추고 보고서만 남긴다.
            return MigrationStageOutcome.ok(issues);
        }

        JsonNode snapshot = parse(payloads.require(work.itemId(), MigrationPayloadKind.SNAPSHOT).body())
                .path("content");
        MigrationJob job = jobs.findById(work.jobId()).orElseThrow();

        Optional<MigrationObjectMapping> existing = mappings.findBySourceKey(
                MigrationObjectMapping.sourceKeyFor(work.provider(), work.sourceInstanceId(),
                        work.externalObjectId()));
        if (existing.isPresent() && work.sourceChecksum().equals(existing.get().getSourceChecksum())
                && existing.get().getTargetPageId() != null
                && pages.existsById(existing.get().getTargetPageId())) {
            // 원본도 그대로고 대상 문서도 살아 있다. 손대지 않는 것이 정답이다 — 다시 쓰면 아무것도
            // 안 바뀐 리비전이 쌓이고 "수정됨" 알림 대상이 늘어난다.
            return MigrationStageOutcome.page(existing.get().getTargetPageId(), issues);
        }

        ImportedPageWriter.ImportedPage page = toImportedPage(work, job, snapshot, rendered.markdown(),
                issues);
        long pageId;
        if (existing.isPresent() && existing.get().getTargetPageId() != null
                && pages.existsById(existing.get().getTargetPageId())) {
            String note = "컨플루언스 재이관 v" + snapshot.path("version").path("number").asInt(1);
            ImportedPageWriter.ImportResult result =
                    pageWriter.update(existing.get().getTargetPageId(), page, note);
            pageId = result.pageId();
            issues.addAll(result.issues());
        } else {
            ImportedPageWriter.ImportResult result = pageWriter.create(page);
            pageId = result.pageId();
            issues.addAll(result.issues());
        }
        // object map은 여기서 바로 갱신한다. worker는 DONE에 닿을 때 한 번 더 부르는데(멱등),
        // 그 사이의 VERIFY가 실패해 재시도되면 이 항목의 자식들이 부모를 못 찾는다.
        objectMappings.upsert(work.provider(), work.sourceInstanceId(), work.externalObjectId(),
                work.sourceVersion(), work.sourceChecksum(), pageId, work.jobId());
        return MigrationStageOutcome.page(pageId, issues);
    }

    private ImportedPageWriter.ImportedPage toImportedPage(MigrationStageWork work, MigrationJob job,
                                                           JsonNode snapshot, String markdown,
                                                           List<MigrationStageIssue> issues) {
        Long parentId = resolveParent(work, snapshot, issues);
        JsonNode createdBy = snapshot.path("history").path("createdBy");
        String displayName = createdBy.path("displayName").asText("");
        // 이메일로 우리 사용자를 찾는 창구가 아직 없다(PrincipalDirectory엔 id 조회만 있다).
        // 계정을 새로 만들지 않는 것이 이 모듈의 전제이므로(기획 §2 제외), M1은 잡 요청자를
        // 작성자로 두고 원본 이름은 리비전 편집자 이름으로 남긴다.
        issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.AUTHOR_UNMAPPED,
                "user:" + (displayName.isBlank() ? "unknown" : displayName)));

        List<String> labels = new ArrayList<>();
        for (JsonNode label : snapshot.path("metadata").path("labels").path("results")) {
            String name = label.path("name").asText("");
            if (!name.isBlank()) {
                labels.add(name);
            }
        }
        Instant createdAt = parseInstant(snapshot.path("history").path("createdDate").asText(""));
        Instant updatedAt = parseInstant(snapshot.path("version").path("when").asText(""));
        return new ImportedPageWriter.ImportedPage(job.getTargetSpaceId(), parentId,
                work.externalObjectId(), snapshot.path("title").asText(""), markdown,
                job.getRequestedBy(), displayName, createdAt, updatedAt, labels);
    }

    /**
     * 부모는 조상 목록의 마지막 항목이 이미 옮겨졌을 때만 정해진다. 발견이 조상 깊이 순으로
     * 담으므로 정상 흐름에서는 늘 있고, 없다면 그 조상이 데드레터로 빠졌다는 뜻이다 —
     * 그때 문서를 버리지 않고 루트에 두는 편이 낫다(트리는 나중에 옮길 수 있다).
     */
    private Long resolveParent(MigrationStageWork work, JsonNode snapshot,
                               List<MigrationStageIssue> issues) {
        JsonNode ancestors = snapshot.path("ancestors");
        if (!ancestors.isArray() || ancestors.isEmpty()) {
            return null;
        }
        String parentExternalId = ancestors.get(ancestors.size() - 1).path("id").asText("");
        if (parentExternalId.isBlank()) {
            return null;
        }
        Optional<Long> parentPageId = mappings.findBySourceKey(MigrationObjectMapping.sourceKeyFor(
                        work.provider(), work.sourceInstanceId(), parentExternalId))
                .map(MigrationObjectMapping::getTargetPageId)
                .filter(id -> id != null && pages.existsById(id));
        if (parentPageId.isEmpty()) {
            issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.PARENT_NOT_FOUND,
                    "page:" + parentExternalId));
            return null;
        }
        return parentPageId.get();
    }

    /** 원본 시각을 못 읽으면 지금으로 둔다 — 시각 하나 때문에 문서를 통째로 못 옮기게 하지 않는다. */
    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        try {
            return java.time.OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException exception) {
            return Instant.now();
        }
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw MigrationStageException.permanent(ConfluenceDcIssues.IR_INVALID);
        }
    }

}
