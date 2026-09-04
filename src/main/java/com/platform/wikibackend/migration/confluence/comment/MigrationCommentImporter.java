package com.platform.wikibackend.migration.confluence.comment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.wikibackend.comment.CommentService;
import com.platform.wikibackend.migration.MigrationPayloadStore;
import com.platform.wikibackend.migration.confluence.handler.ConfluenceDcIssues;
import com.platform.wikibackend.migration.model.MigrationObjectMapping;
import com.platform.wikibackend.migration.model.MigrationPayloadKind;
import com.platform.wikibackend.migration.repository.MigrationObjectMappingRepository;
import com.platform.wikibackend.migration.worker.MigrationObjectMappingWriter;
import com.platform.wikibackend.migration.worker.MigrationStageIssue;
import com.platform.wikibackend.migration.worker.MigrationStageWork;
import com.platform.wikibackend.repository.PageCommentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EXTRACT가 받아 둔 원본 댓글을 대상 문서에 단다(M3 §5.2). RESOLVE에서 문서를 쓴 뒤에 돈다.
 *
 * 멱등의 근거는 object map의 {@code comment:{원본 id}} 행이다. 잡마다 item이 새로 생기므로
 * item에 붙은 payload로는 "지난 잡에서 이미 달았다"를 알 수 없다 — 재이관이 댓글을 두 벌로
 * 만드는 것이 이 단계에서 가장 흔한 사고다.
 *
 * 이미 옮긴 댓글은 **손대지 않는다**. 원본에서 수정된 댓글도 그대로 둔다 — 우리 쪽에서 사람이
 * 이어 단 대화를 이관이 덮어쓰는 것보다, 원본의 나중 수정이 반영되지 않는 편이 낫다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MigrationCommentImporter {

    private final MigrationPayloadStore payloads;
    private final MigrationObjectMappingRepository mappings;
    private final MigrationObjectMappingWriter mappingWriter;
    private final PageCommentRepository comments;
    private final CommentService commentService;
    private final ObjectMapper objectMapper;

    /**
     * @param requesterId 잡 요청자 — 옮긴 댓글의 작성자 id가 된다(원본 이름은 스냅샷 컬럼에 남는다)
     * @return 그 과정의 손실
     */
    public List<MigrationStageIssue> importComments(MigrationStageWork work, long pageId,
                                                    long requesterId) {
        List<MigrationCommentPayload.Entry> entries = read(work.itemId()).comments();
        if (entries.isEmpty()) {
            return List.of();
        }
        List<MigrationStageIssue> issues = new ArrayList<>();
        Map<String, Long> targetIds = existing(work, entries);

        // 최상위를 먼저 만든다 — 답글이 부모의 대상 id를 찾을 수 있어야 한다. 원본 목록 순서가
        // 작성 순이 아닐 수도 있어(사이트 설정) 순서에 기대지 않고 두 번 훑는다.
        for (MigrationCommentPayload.Entry entry : entries) {
            if (entry.parentId() == null) {
                create(work, pageId, requesterId, entry, null, targetIds, issues);
            }
        }
        for (MigrationCommentPayload.Entry entry : entries) {
            if (entry.parentId() == null) {
                continue;
            }
            Long parentTargetId = targetIds.get(entry.parentId());
            if (parentTargetId == null) {
                // 부모가 변환에 실패했거나 원본에서 사라졌다. 답글을 버리지 않고 최상위로 올린다 —
                // 자리를 잃는 것이 말을 잃는 것보다 낫다.
                issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.COMMENT_REPLY_FLATTENED,
                        "comment:" + entry.id()));
            }
            create(work, pageId, requesterId, entry, parentTargetId, targetIds, issues);
        }
        return issues;
    }

    private void create(MigrationStageWork work, long pageId, long requesterId,
                        MigrationCommentPayload.Entry entry, Long parentTargetId,
                        Map<String, Long> targetIds, List<MigrationStageIssue> issues) {
        if (targetIds.containsKey(entry.id())) {
            return; // 지난 잡에서 이미 달았다.
        }
        String body = entry.markdown() == null ? "" : entry.markdown().trim();
        if (body.isEmpty()) {
            issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.COMMENT_NOT_MIGRATED,
                    "comment:" + entry.id()));
            return;
        }
        long commentId;
        try {
            commentId = commentService.createImported(pageId, parentTargetId, requesterId,
                    entry.authorName(), body, parseInstant(entry.createdAt()));
        } catch (RuntimeException exception) {
            log.warn("원본 댓글을 옮기지 못했다: page={} comment={}", pageId, entry.id(), exception);
            issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.COMMENT_NOT_MIGRATED,
                    "comment:" + entry.id()));
            return;
        }
        targetIds.put(entry.id(), commentId);
        mappingWriter.upsertComment(work.provider(), work.sourceInstanceId(), entry.id(),
                checksumOf(entry.id()), commentId, work.jobId());
    }

    /**
     * 이미 옮겨 둔 댓글의 대상 id. 매핑은 있는데 댓글이 사라졌으면(사람이 지웠다) 없는 것으로
     * 본다 — 다시 달아 준다. 지운 댓글이 되살아나는 것이 마음에 걸리지만, 그 판단을 하려면
     * "지웠다"와 "옮기다 실패했다"를 구분할 근거가 있어야 하고 지금은 없다.
     */
    private Map<String, Long> existing(MigrationStageWork work,
                                       List<MigrationCommentPayload.Entry> entries) {
        Map<String, Long> found = new LinkedHashMap<>();
        for (MigrationCommentPayload.Entry entry : entries) {
            mappings.findBySourceKey(MigrationObjectMapping.sourceKeyFor(work.provider(),
                            work.sourceInstanceId(), MigrationObjectMapping.commentObjectId(entry.id())))
                    .map(MigrationObjectMapping::getTargetCommentId)
                    .filter(id -> id != null && comments.existsById(id))
                    .ifPresent(id -> found.put(entry.id(), id));
        }
        return found;
    }

    private MigrationCommentPayload read(long itemId) {
        return payloads.read(itemId, MigrationPayloadKind.COMMENTS)
                .map(payload -> {
                    try {
                        return objectMapper.readValue(payload.body(), MigrationCommentPayload.class);
                    } catch (JsonProcessingException exception) {
                        // 형식을 못 읽으면 없는 것으로 본다 — 깨진 목록으로 절반만 다는 것보다 낫다.
                        return MigrationCommentPayload.empty();
                    }
                })
                .orElseGet(MigrationCommentPayload::empty);
    }

    /** 원본 시각을 못 읽으면 지금으로 둔다 — 시각 하나 때문에 댓글을 통째로 버리지 않는다. */
    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException exception) {
            return Instant.now();
        }
    }

    /** 매핑 행의 checksum 자리. 댓글은 내용이 바뀌어도 다시 쓰지 않으므로 id만으로 결정한다. */
    private static String checksumOf(String sourceCommentId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    ("comment:" + sourceCommentId).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", exception);
        }
    }
}
