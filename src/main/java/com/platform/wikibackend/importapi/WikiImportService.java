package com.platform.wikibackend.importapi;

import com.platform.common.error.NotFoundException;
import com.platform.wikibackend.attachment.AttachmentLifecycleStatus;
import com.platform.wikibackend.attachment.AttachmentReferences;
import com.platform.wikibackend.attachment.AttachmentService;
import com.platform.wikibackend.audit.AuditService;
import com.platform.wikibackend.comment.CommentService;
import com.platform.wikibackend.domain.Attachment;
import com.platform.wikibackend.domain.AuditAction;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageComment;
import com.platform.wikibackend.domain.PageType;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.importapi.ImportedPageWriter.ImportedPage;
import com.platform.wikibackend.importapi.ImportedPageWriter.ImportedRevision;
import com.platform.wikibackend.importapi.dto.WikiImportRequests;
import com.platform.wikibackend.importapi.dto.WikiImportResponses;
import com.platform.wikibackend.permission.PageRestrictionService;
import com.platform.wikibackend.permission.dto.RestrictionPrincipal;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.repository.PageCommentRepository;
import com.platform.wikibackend.repository.PageLabelRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 이관 데이터를 원본 그대로 받아 넣는 내부 쓰기 창구(W29 X1, 설계 §2).
 *
 * 스스로 도메인 규칙을 만들지 않는다 — 이미 이관 엔진이 쓰던 내부 경로
 * ({@link ImportedPageWriter}, {@link AttachmentService#registerStored},
 * {@link CommentService#createImported}, {@link PageRestrictionService#replaceImported})를
 * HTTP로 감싸기만 한다. 엔진이 별도 서비스로 나간 뒤에도 두 입구가 같은 코드를 타야
 * "위키 안에서는 되는데 API로는 다르게 들어간다"가 생기지 않는다.
 *
 * 권한 검사가 없다. 부르는 쪽은 이미 org-service로 대상 스페이스 ADMIN을 확인한 잡 워커이고,
 * 이 경로는 게이트웨이·nginx가 라우팅하지 않는다. 문 자체는 공유 비밀
 * ({@link com.platform.wikibackend.config.InternalTokenFilter})이 지킨다.
 */
@Service
@RequiredArgsConstructor
public class WikiImportService {

    private final ImportedPageWriter writer;
    private final AttachmentService attachments;
    private final AttachmentRepository attachmentRows;
    private final CommentService comments;
    private final PageCommentRepository commentRows;
    private final PageRestrictionService restrictions;
    private final PageRepository pages;
    private final PageLabelRepository labels;
    private final SpaceRepository spaces;
    private final AuditService audit;

    // ── 페이지 ──

    /**
     * 새 문서. importKey가 오면 멱등이다 — 같은 키의 문서가 이미 있으면 아무것도 쓰지 않고
     * 그 문서를 돌려준다(EXISTING).
     *
     * 방어가 두 겹인 이유는 경합이다. 조회 후 INSERT 사이에 다른 요청이 같은 키를 넣을 수 있고
     * (잡 재시도, 두 워커가 같은 항목을 집는 경우), 그때는 부분 유니크 인덱스(V38)가 거부한다.
     * 그 거부를 오류로 올리면 엔진은 "실패"로 보고 또 재시도한다 — 실제로는 이미 들어가 있는데도.
     * 그래서 위반을 잡아 다시 찾아보고 같은 결론(EXISTING)으로 수렴시킨다.
     */
    public WikiImportResponses.PageWritten createPage(long actorId, WikiImportRequests.CreatePage req) {
        long spaceId = required(req.spaceId(), "spaceId");
        requireSpace(spaceId);
        if (req.parentId() != null) {
            requirePage(req.parentId());
        }
        Instant createdAt = required(req.createdAt(), "createdAt");
        Instant updatedAt = required(req.updatedAt(), "updatedAt");
        boolean mapped = req.authorId() != null;
        long authorId = mapped ? req.authorId() : actorId;
        // 저장 전에 다듬는다 — 조회 키와 저장 키가 다르면 멱등이 성립하지 않는다.
        String importKey = normalizeKey(req.importKey());

        Optional<WikiImportResponses.PageWritten> already = existingByKey(importKey);
        if (already.isPresent()) {
            return already.get();
        }

        ImportedPage source = new ImportedPage(spaceId, req.parentId(), importKey,
                required(req.title(), "title"), text(req.content()), authorId,
                req.importedAuthorName(), mapped, req.sourceUrl(), createdAt, updatedAt,
                req.labels(), req.sortOrder(), req.type() == null ? PageType.PAGE : req.type(),
                history(req.revisions()));

        ImportedPageWriter.ImportResult result;
        try {
            result = writer.create(source);
        } catch (DataIntegrityViolationException conflict) {
            // 경합. 방금 누가 같은 키로 넣었다는 뜻이므로 그 문서가 정답이다.
            return existingByKey(importKey).orElseThrow(() -> conflict);
        }
        Page page = requirePage(result.pageId());
        // 문서 한 건당 감사 기록 한 줄 — 첨부·댓글·본문 정리까지 남기면 목록이 이관으로 덮인다.
        audit.recordPage(actorId, AuditAction.IMPORTED, page, "import:page.create");
        return new WikiImportResponses.PageWritten(page.getId(), page.getVersion(),
                WikiImportResponses.PageWritten.CREATED, result.issues());
    }

    public WikiImportResponses.PageWritten reimportPage(long actorId, long pageId,
                                                        WikiImportRequests.ReimportPage req) {
        Page existing = requirePage(pageId);
        Instant updatedAt = required(req.updatedAt(), "updatedAt");
        boolean mapped = req.editorId() != null;
        long editorId = mapped ? req.editorId() : actorId;

        ImportedPage source = new ImportedPage(existing.getSpaceId(), existing.getParentId(), null,
                required(req.title(), "title"), text(req.content()), editorId,
                req.editorName(), mapped, req.sourceUrl(), existing.getCreatedAt(), updatedAt,
                req.labels(), null, existing.getType(), List.of());

        ImportedPageWriter.ImportResult result = writer.update(pageId, source, req.changeNote());
        Page page = requirePage(pageId);
        return new WikiImportResponses.PageWritten(page.getId(), page.getVersion(),
                WikiImportResponses.PageWritten.UPDATED, result.issues());
    }

    public WikiImportResponses.ContentWritten rewriteContent(long actorId, long pageId,
                                                             WikiImportRequests.RewriteContent req) {
        requirePage(pageId);
        String content = text(req.content());
        boolean bump = Boolean.TRUE.equals(req.bumpVersion());
        boolean changed = bump
                ? writer.rewriteBodyAsRevision(pageId, content, actorId, req.changeNote())
                : writer.rewriteBody(pageId, content);
        Page page = requirePage(pageId);
        return new WikiImportResponses.ContentWritten(page.getId(), page.getVersion(), changed);
    }

    public WikiImportResponses.OrderWritten reorder(long pageId, WikiImportRequests.Reorder req) {
        requirePage(pageId);
        boolean changed = writer.resequence(pageId, req.sortOrder());
        Page page = requirePage(pageId);
        return new WikiImportResponses.OrderWritten(page.getId(), page.getSortOrder(), changed);
    }

    // ── 첨부 ──

    /**
     * 원본 첨부 한 건. 엔진이 원본에서 받아 그대로 올린다 — 위키 저장소 좌표를 엔진이 알 필요가
     * 없어야 두 서비스가 저장소 구현으로 묶이지 않는다.
     *
     * 같은 이름·같은 checksum이면 저장소에 쓰지도 않고 UNCHANGED로 끝낸다. 재이관은 대부분
     * 여기로 떨어지므로, 스테이징부터 하고 registerStored가 버리게 두면 이관을 돌릴 때마다
     * 저장소에 고아 객체가 쌓인다.
     */
    public WikiImportResponses.AttachmentRegistered registerAttachment(
            long actorId, long pageId, MultipartFile file, String filename, String checksum) {
        requirePage(pageId);
        String name = (filename == null || filename.isBlank())
                ? (file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename())
                : filename;
        byte[] content = read(file);
        String actual = sha256(content);
        if (checksum != null && !checksum.isBlank() && !checksum.equalsIgnoreCase(actual)) {
            throw new IllegalArgumentException("첨부 checksum이 실제 내용과 다릅니다: " + name);
        }

        Optional<Attachment> existing = attachmentRows.findByPageIdAndFilenameAndLifecycleStatus(
                pageId, name, AttachmentLifecycleStatus.CONFIRMED);
        if (existing.isPresent() && actual.equals(existing.get().getChecksumSha256())) {
            return registered(existing.get().getId(), "UNCHANGED");
        }

        AttachmentService.StagedObject staged = attachments.stageImported(content);
        long id = attachments.registerStored(actorId, pageId, name, staged.contentType(),
                staged.sizeBytes(), staged.checksum(), staged.stored());
        return registered(id, existing.isPresent() ? "NEW_VERSION" : "CREATED");
    }

    // ── 댓글 ──

    public WikiImportResponses.CommentWritten createComment(long actorId, long pageId,
                                                            WikiImportRequests.CreateComment req) {
        requirePage(pageId);
        if (req.parentCommentId() != null && !commentRows.existsById(req.parentCommentId())) {
            throw new NotFoundException("코멘트 없음: " + req.parentCommentId());
        }
        boolean mapped = req.authorId() != null;
        // 대조된 작성자는 우리 사용자로 보여야 한다 — 이름 스냅샷은 못 찾았을 때만 남긴다.
        long commentId = comments.createImported(pageId, req.parentCommentId(),
                mapped ? req.authorId() : actorId, mapped ? null : req.authorName(),
                required(req.body(), "body"), req.createdAt());
        return new WikiImportResponses.CommentWritten(commentId);
    }

    @Transactional(readOnly = true)
    public WikiImportResponses.CommentView comment(long commentId) {
        PageComment comment = commentRows.findById(commentId)
                .orElseThrow(() -> new NotFoundException("코멘트 없음: " + commentId));
        return new WikiImportResponses.CommentView(comment.getId(), comment.getPageId(),
                comment.getParentId(), comment.getCreatedAt());
    }

    // ── 제한 ──

    public void replaceRestrictions(long actorId, long pageId,
                                    WikiImportRequests.ReplaceRestrictions req) {
        requirePage(pageId);
        restrictions.replaceImported(pageId, principals(req.view()), principals(req.edit()), actorId);
    }

    // ── 검증 조회 ──

    @Transactional(readOnly = true)
    public WikiImportResponses.PageView page(long pageId) {
        Page page = requirePage(pageId);
        List<WikiImportResponses.AttachmentView> files = attachmentRows.findByPageId(pageId).stream()
                .filter(a -> a.getLifecycleStatus() == AttachmentLifecycleStatus.CONFIRMED)
                .map(a -> new WikiImportResponses.AttachmentView(a.getId(), a.getFilename(),
                        a.getChecksumSha256()))
                .toList();
        List<String> names = labels.findByPageIdOrderByName(pageId).stream()
                .map(label -> label.getName())
                .toList();
        return new WikiImportResponses.PageView(page.getId(), page.getSpaceId(), page.getParentId(),
                page.getTitle(), page.getType(), page.getContent().length(), page.getVersion(),
                page.getSortOrder(), page.getImportKey(), names, files,
                commentRows.countByPageId(pageId));
    }

    @Transactional(readOnly = true)
    public WikiImportResponses.PageMatches pagesByTitle(long spaceId, String title) {
        requireSpace(spaceId);
        if (title == null || title.isBlank()) {
            return new WikiImportResponses.PageMatches(List.of());
        }
        return new WikiImportResponses.PageMatches(
                pages.findBySpaceIdAndTitleIgnoringCase(spaceId, title).stream()
                        .map(p -> new WikiImportResponses.PageMatch(p.getId(), p.getTitle(), p.getType()))
                        .toList());
    }

    @Transactional(readOnly = true)
    public WikiImportResponses.SpaceView space(long spaceId) {
        Space space = requireSpace(spaceId);
        return new WikiImportResponses.SpaceView(space.getId(), space.getKey(), space.getName());
    }

    // ── 내부 ──

    /**
     * 지난 버전을 원본 순서로 눕힌다. version이 오면 그것으로 정렬하고(원본 목록이 최신부터일 수
     * 있다), 없으면 받은 순서를 믿는다 — 여기서 순서가 뒤집히면 이력이 거꾸로 읽힌다.
     */
    private static List<ImportedRevision> history(List<WikiImportRequests.Revision> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<WikiImportRequests.Revision> ordered = new ArrayList<>(raw);
        if (ordered.stream().allMatch(r -> r.version() != null)) {
            ordered.sort(Comparator.comparingInt(WikiImportRequests.Revision::version));
        }
        return ordered.stream()
                .map(r -> new ImportedRevision(r.title(), text(r.content()), r.editorId(),
                        r.editorName(), r.changeNote(), r.savedAt()))
                .toList();
    }

    private static WikiImportResponses.AttachmentRegistered registered(long id, String outcome) {
        return new WikiImportResponses.AttachmentRegistered(id,
                AttachmentReferences.inlineUrl(id), AttachmentReferences.downloadUrl(id), outcome);
    }

    private static List<RestrictionPrincipal> principals(List<RestrictionPrincipal> raw) {
        if (raw == null) {
            return List.of();
        }
        // 타입 문자열은 여기서 검증한다 — 저장 직전에 터지면 절반만 걸린 제한이 남는다.
        raw.forEach(RestrictionPrincipal::toType);
        return raw;
    }

    /** 키는 엔진이 정하는 불투명한 문자열이다 — 다듬기만 하고 형식은 보지 않는다. */
    private static String normalizeKey(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 같은 키의 살아 있는 문서(휴지통 행은 @SQLRestriction이 뺀다 — 부분 유니크 인덱스와 같은 범위). */
    private Optional<WikiImportResponses.PageWritten> existingByKey(String importKey) {
        if (importKey == null) {
            return Optional.empty();
        }
        return pages.findByImportKey(importKey)
                .map(page -> new WikiImportResponses.PageWritten(page.getId(), page.getVersion(),
                        WikiImportResponses.PageWritten.EXISTING, List.of()));
    }

    private Page requirePage(long pageId) {
        return pages.findById(pageId)
                .orElseThrow(() -> new NotFoundException("페이지 없음: " + pageId));
    }

    private Space requireSpace(long spaceId) {
        return spaces.findById(spaceId)
                .orElseThrow(() -> new NotFoundException("스페이스 없음: " + spaceId));
    }

    /** 본문 없음과 빈 본문은 같다 — 폴더처럼 내용이 없는 문서도 옮겨야 한다. */
    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static <T> T required(T value, String field) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            throw new IllegalArgumentException(field + "은(는) 필수입니다");
        }
        return value;
    }

    private static byte[] read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("첨부 파일이 비어 있습니다");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("이관 첨부 스트림 읽기 실패", e);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }
}
