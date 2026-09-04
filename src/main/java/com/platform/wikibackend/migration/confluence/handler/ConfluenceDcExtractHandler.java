package com.platform.wikibackend.migration.confluence.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.wikibackend.migration.MigrationPayloadStore;
import com.platform.wikibackend.migration.confluence.ConfluenceStorageNormalizer;
import com.platform.wikibackend.migration.confluence.ImportedPageWriter;
import com.platform.wikibackend.migration.confluence.dc.ConfluenceDcClient;
import com.platform.wikibackend.migration.confluence.dc.ConfluenceDcCredentials;
import com.platform.wikibackend.migration.model.MigrationPayloadKind;
import com.platform.wikibackend.migration.model.MigrationProvider;
import com.platform.wikibackend.migration.model.MigrationSource;
import com.platform.wikibackend.migration.model.MigrationStage;
import com.platform.wikibackend.migration.repository.MigrationSourceRepository;
import com.platform.wikibackend.migration.worker.MigrationStageException;
import com.platform.wikibackend.migration.worker.MigrationStageHandler;
import com.platform.wikibackend.migration.worker.MigrationStageIssue;
import com.platform.wikibackend.migration.worker.MigrationStageOutcome;
import com.platform.wikibackend.migration.worker.MigrationStageWork;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 원본 DC에서 페이지 하나를 읽어 **스냅샷 v1**으로 저장한다.
 *
 * 스냅샷의 정본 형태는 `fixtures/migration/confluence/confluence-page-snapshot-v1.json`이고,
 * 정규화기가 읽는 필드만 담는다 — DC 응답을 통째로 담으면 `_links` 같은 것까지 들어와
 * 정규화기의 "모르는 필드는 거부" 규칙에 걸린다.
 */
@Component
@RequiredArgsConstructor
public class ConfluenceDcExtractHandler implements MigrationStageHandler {

    /** IR 계약(title maxLength)과 page.title(varchar) 상한이 같은 값이다. */
    private static final int MAX_TITLE_LENGTH = ImportedPageWriter.MAX_TITLE_LENGTH;

    private final ConfluenceDcClient client;
    private final MigrationSourceRepository sources;
    private final MigrationPayloadStore payloads;
    private final ObjectMapper objectMapper;

    @Override
    public MigrationProvider provider() {
        return MigrationProvider.CONFLUENCE_DC;
    }

    @Override
    public MigrationStage stage() {
        return MigrationStage.EXTRACT;
    }

    @Override
    public MigrationStageOutcome handle(MigrationStageWork work) {
        MigrationSource source = sources.findById(work.jobId())
                .orElseThrow(() -> MigrationStageException.permanent(ConfluenceDcIssues.SOURCE_MISSING));
        ConfluenceDcCredentials credentials = new ConfluenceDcCredentials(
                source.getBaseUrl(), source.getSpaceKey(), source.getAuthToken());

        JsonNode content = client.content(credentials, work.externalObjectId());
        List<MigrationStageIssue> issues = new ArrayList<>();
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("snapshotVersion", ConfluenceStorageNormalizer.SNAPSHOT_VERSION);
        snapshot.set("content", trim(content, work.externalObjectId(), issues));
        payloads.write(work.itemId(), MigrationPayloadKind.SNAPSHOT, snapshot.toString());

        String observed = content.path("version").path("number").asText("");
        if (work.sourceVersion() != null && !work.sourceVersion().isBlank()
                && !work.sourceVersion().equals(observed)) {
            // 발견 이후 누군가 원본을 고쳤다. 최신본을 옮기는 것이 맞으므로 멈추지 않고 알리기만 한다.
            issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.SOURCE_VERSION_DRIFT,
                    "page:" + work.externalObjectId()));
        }
        return MigrationStageOutcome.ok(issues);
    }

    /**
     * 정규화기가 읽는 필드만 남긴다. DC 응답에는 `_links`·`_expandable`·`extensions`가 딸려 오는데,
     * 그대로 담으면 스냅샷이 원본 응답의 버전 차이에 그대로 노출된다 — 스냅샷은 우리 계약이어야 한다.
     */
    private ObjectNode trim(JsonNode content, String externalObjectId, List<MigrationStageIssue> issues) {
        ObjectNode out = objectMapper.createObjectNode();
        copyText(content, out, "id");
        copyText(content, out, "type");
        copyText(content, out, "status");
        // 제목 상한은 스냅샷에서 이미 지킨다. IR 계약(title maxLength 255)과 page.title(varchar 255)이
        // 둘 다 255라, 여기서 자르지 않으면 NORMALIZE가 IR 검증 실패로 항목을 통째로 데드레터시킨다 —
        // 제목이 긴 것은 문서를 못 옮길 이유가 아니다.
        String title = content.path("title").asText("");
        if (title.length() > MAX_TITLE_LENGTH) {
            issues.add(MigrationStageIssue.warning(ImportedPageWriter.TITLE_TRUNCATED,
                    "page:" + externalObjectId));
            title = title.substring(0, MAX_TITLE_LENGTH);
        }
        out.put("title", title);

        ObjectNode space = out.putObject("space");
        space.put("key", content.path("space").path("key").asText(""));
        space.put("name", content.path("space").path("name").asText(""));

        ObjectNode version = out.putObject("version");
        version.put("number", content.path("version").path("number").asInt(1));
        version.put("when", content.path("version").path("when").asText(""));

        var ancestors = out.putArray("ancestors");
        for (JsonNode ancestor : content.path("ancestors")) {
            ancestors.addObject().put("id", ancestor.path("id").asText(""));
        }

        ObjectNode history = out.putObject("history");
        history.put("createdDate", content.path("history").path("createdDate").asText(""));
        ObjectNode createdBy = history.putObject("createdBy");
        JsonNode sourceCreatedBy = content.path("history").path("createdBy");
        createdBy.put("username", sourceCreatedBy.path("username").asText(""));
        createdBy.put("displayName", sourceCreatedBy.path("displayName").asText(""));
        createdBy.put("email", sourceCreatedBy.path("email").asText(""));

        ObjectNode metadata = out.putObject("metadata");
        ObjectNode labels = metadata.putObject("labels");
        var labelResults = labels.putArray("results");
        for (JsonNode label : content.path("metadata").path("labels").path("results")) {
            labelResults.addObject().put("name", label.path("name").asText(""));
        }

        ObjectNode body = out.putObject("body");
        ObjectNode storage = body.putObject("storage");
        storage.put("value", content.path("body").path("storage").path("value").asText(""));
        storage.put("representation",
                content.path("body").path("storage").path("representation").asText("storage"));

        ObjectNode children = out.putObject("children");
        ObjectNode attachment = children.putObject("attachment");
        var attachmentResults = attachment.putArray("results");
        for (JsonNode file : content.path("children").path("attachment").path("results")) {
            ObjectNode row = attachmentResults.addObject();
            row.put("id", file.path("id").asText(""));
            row.put("title", file.path("title").asText(""));
            row.put("mediaType", file.path("extensions").path("mediaType").asText(""));
            row.put("fileSize", file.path("extensions").path("fileSize").asLong(0));
        }
        return out;
    }

    private void copyText(JsonNode from, ObjectNode to, String field) {
        to.put(field, from.path(field).asText(""));
    }
}
