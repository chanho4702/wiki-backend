package com.platform.wikibackend.migration.confluence.media;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.wikibackend.attachment.AttachmentReferences;
import com.platform.wikibackend.attachment.AttachmentService;
import com.platform.wikibackend.migration.MigrationPayloadStore;
import com.platform.wikibackend.migration.confluence.handler.ConfluenceDcIssues;
import com.platform.wikibackend.migration.confluence.link.MarkdownLinkTargets;
import com.platform.wikibackend.migration.model.MigrationPayloadKind;
import com.platform.wikibackend.migration.worker.MigrationStageIssue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 스테이징해 둔 첨부를 대상 문서의 첨부 레코드로 만들고, 본문의 `attachment:{파일명}` 참조를
 * 우리 첨부 URL로 바꾼다(M2 §4.1).
 *
 * 순서가 이렇게 될 수밖에 없는 이유: 첨부 레코드는 페이지에 매달리므로 페이지가 먼저 있어야 하고,
 * 본문의 참조는 첨부 **id**로 걸리므로 레코드가 먼저 있어야 한다. 그래서 문서를 한 번 쓰고,
 * 첨부를 등록하고, 본문만 다시 눌러 준다(새 리비전 없이 — §{@link
 * com.platform.wikibackend.migration.confluence.ImportedPageWriter#rewriteBody}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MigrationAttachmentImporter {

    /** 본문에 남아 있는 미해결 참조의 스킴. IR→마크다운 writer가 이 꼴로 쓴다. */
    public static final String ATTACHMENT_SCHEME = "attachment:";

    private final MigrationPayloadStore payloads;
    private final AttachmentService attachments;
    private final ObjectMapper objectMapper;

    /**
     * 이 항목의 첨부를 전부 등록한다. 파일 하나가 실패해도 나머지는 계속 간다 — 첨부 한 건 때문에
     * 문서를 통째로 못 옮기게 하지 않는다.
     */
    public Registered register(long itemId, long pageId, long userId) {
        MigrationMediaManifest manifest = readManifest(itemId);
        Map<String, Registered.Entry> byFilename = new LinkedHashMap<>();
        List<MigrationStageIssue> issues = new ArrayList<>();
        for (MigrationMediaManifest.Entry entry : manifest.files()) {
            try {
                long attachmentId = attachments.registerStored(userId, pageId, entry.filename(),
                        entry.contentType(), entry.size(), entry.checksum(), entry.storedObject());
                byFilename.put(entry.filename(),
                        new Registered.Entry(attachmentId, entry.contentType()));
            } catch (RuntimeException exception) {
                log.warn("첨부 등록 실패 — 본문 참조는 그대로 둔다: page={} file={}",
                        pageId, entry.filename(), exception);
                issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.ATTACHMENT_NOT_COPIED,
                        "attachment:" + entry.filename()));
            }
        }
        return new Registered(byFilename, issues);
    }

    /**
     * 본문의 `attachment:{파일명}`을 실제 주소로 바꾼다.
     *
     * 이미지는 인라인 주소로, 그 밖의 파일은 내려받기 주소로 간다 — inline 엔드포인트는 안전한
     * 형식만 열어 주므로 문서 파일을 인라인으로 걸면 열 때 400이 난다.
     */
    public Rewritten rewrite(String markdown, Registered registered) {
        List<MigrationStageIssue> issues = new ArrayList<>();
        String rewritten = MarkdownLinkTargets.rewrite(markdown, target -> {
            if (target == null || !target.startsWith(ATTACHMENT_SCHEME)) {
                return null;
            }
            String filename = target.substring(ATTACHMENT_SCHEME.length());
            Registered.Entry entry = registered.byFilename().get(filename);
            if (entry == null) {
                issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.ATTACHMENT_REF_UNRESOLVED,
                        "attachment:" + filename));
                return null;
            }
            return entry.contentType() != null && entry.contentType().startsWith("image/")
                    ? AttachmentReferences.inlineUrl(entry.attachmentId())
                    : AttachmentReferences.downloadUrl(entry.attachmentId());
        });
        return new Rewritten(rewritten, issues);
    }

    private MigrationMediaManifest readManifest(long itemId) {
        return payloads.read(itemId, MigrationPayloadKind.MEDIA_MANIFEST)
                .map(payload -> {
                    try {
                        return objectMapper.readValue(payload.body(), MigrationMediaManifest.class);
                    } catch (JsonProcessingException exception) {
                        return MigrationMediaManifest.empty();
                    }
                })
                .orElseGet(MigrationMediaManifest::empty);
    }

    /** 등록된 첨부 — 파일명으로 찾는다(본문 참조가 파일명으로 걸려 있기 때문). */
    public record Registered(Map<String, Entry> byFilename, List<MigrationStageIssue> issues) {

        public Registered {
            byFilename = byFilename == null ? Map.of() : Map.copyOf(byFilename);
            issues = issues == null ? List.of() : List.copyOf(issues);
        }

        public boolean isEmpty() {
            return byFilename.isEmpty();
        }

        public record Entry(long attachmentId, String contentType) {
        }
    }

    public record Rewritten(String markdown, List<MigrationStageIssue> issues) {

        public Rewritten {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }
}
