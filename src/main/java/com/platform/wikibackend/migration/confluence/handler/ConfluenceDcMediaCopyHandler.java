package com.platform.wikibackend.migration.confluence.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.wikibackend.migration.MigrationPayloadStore;
import com.platform.wikibackend.migration.model.MigrationPayloadKind;
import com.platform.wikibackend.migration.model.MigrationProvider;
import com.platform.wikibackend.migration.model.MigrationStage;
import com.platform.wikibackend.migration.worker.MigrationStageException;
import com.platform.wikibackend.migration.worker.MigrationStageHandler;
import com.platform.wikibackend.migration.worker.MigrationStageIssue;
import com.platform.wikibackend.migration.worker.MigrationStageOutcome;
import com.platform.wikibackend.migration.worker.MigrationStageWork;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * M1의 자리표시. 첨부 본체 복사는 M2이고, 여기서는 **무엇을 못 옮겼는지**만 보고한다.
 *
 * 단계 자체를 빼지 않는 이유: 빼면 registry에 handler가 없어 모든 항목이 STAGE_HANDLER_UNAVAILABLE로
 * 데드레터된다. 그리고 관리자는 "첨부 200개가 안 넘어왔다"를 보고서에서 봐야지, 이관이 끝난 뒤
 * 깨진 이미지로 알게 되면 안 된다.
 *
 * 경고 대상은 IR의 assets와 스냅샷의 첨부 목록을 합친 것이다. M1에서는 자산을 해결하지 않아
 * IR assets가 늘 비어 있으므로, 스냅샷 쪽을 함께 보지 않으면 경고가 한 건도 안 나온다.
 */
@Component
@RequiredArgsConstructor
public class ConfluenceDcMediaCopyHandler implements MigrationStageHandler {

    private final MigrationPayloadStore payloads;
    private final ObjectMapper objectMapper;

    @Override
    public MigrationProvider provider() {
        return MigrationProvider.CONFLUENCE_DC;
    }

    @Override
    public MigrationStage stage() {
        return MigrationStage.MEDIA_COPY;
    }

    @Override
    public MigrationStageOutcome handle(MigrationStageWork work) {
        Set<String> filenames = new LinkedHashSet<>();
        for (JsonNode asset : parse(payloads.require(work.itemId(), MigrationPayloadKind.IR).body())
                .path("assets")) {
            String filename = asset.path("filename").asText("");
            if (!filename.isBlank()) {
                filenames.add(filename);
            }
        }
        payloads.read(work.itemId(), MigrationPayloadKind.SNAPSHOT).ifPresent(snapshot -> {
            for (JsonNode file : parse(snapshot.body())
                    .path("content").path("children").path("attachment").path("results")) {
                String title = file.path("title").asText("");
                if (!title.isBlank()) {
                    filenames.add(title);
                }
            }
        });

        List<MigrationStageIssue> issues = new ArrayList<>();
        for (String filename : filenames) {
            issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.ATTACHMENT_NOT_COPIED,
                    "attachment:" + filename));
        }
        return MigrationStageOutcome.ok(issues);
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw MigrationStageException.permanent(ConfluenceDcIssues.SNAPSHOT_INVALID);
        }
    }
}
