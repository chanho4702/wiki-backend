package com.platform.wikibackend.attachment;

import com.platform.wikibackend.attachment.dto.AttachmentResponse;
import com.platform.wikibackend.common.NotFoundException;
import com.platform.wikibackend.common.UnsafeInlineMediaTypeException;
import com.platform.wikibackend.domain.Attachment;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.event.EventRelay;
import com.platform.wikibackend.event.WikiEvents;
import com.platform.wikibackend.page.PageService;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.space.SpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class AttachmentService {

    private final AttachmentRepository attachments;
    private final PageService pages;
    private final SpaceService spaces;
    private final AttachmentStorageRouter storage;
    private final EventRelay events;

    public AttachmentResponse upload(long userId, long pageId, MultipartFile file) {
        Page page = pages.getOwned(pageId);
        spaces.require(userId, page.getSpaceId(), WikiAction.EDIT);
        try {
            String contentType;
            try (InputStream probe = file.getInputStream()) {
                contentType = AttachmentMediaTypes.detect(probe);
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StoredObject stored;
            try (InputStream raw = file.getInputStream(); DigestInputStream input = new DigestInputStream(raw, digest)) {
                stored = storage.store(input, file.getSize(), contentType);
            }
            storage.deleteAfterRollback(stored);

            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unnamed";
            String checksum = HexFormat.of().formatHex(digest.digest());
            Attachment saved = attachments.save(
                    Attachment.of(pageId, filename, contentType, file.getSize(), stored, checksum, userId));
            events.afterCommit(WikiEvents.attachmentAdded(userId, saved, page.getSpaceId()));
            return AttachmentResponse.from(saved);
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 스트림 읽기 실패", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(long userId, long pageId) {
        Page page = pages.getOwned(pageId);
        spaces.require(userId, page.getSpaceId(), WikiAction.VIEW);
        return attachments.findByPageId(pageId).stream().map(AttachmentResponse::from).toList();
    }

    /** 다운로드용 — [메타, 리소스] 쌍. */
    @Transactional(readOnly = true)
    public DownloadItem download(long userId, long attachmentId) {
        Attachment a = attachments.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("첨부 없음: " + attachmentId));
        Page page = pages.getOwned(a.getPageId());
        spaces.require(userId, page.getSpaceId(), WikiAction.VIEW);
        return new DownloadItem(a, storage.open(a.getStorageBackend(), a.getStorageBucket(),
                a.getStorageKey(), a.getStorageVersion()));
    }

    @Transactional(readOnly = true)
    public DownloadItem inline(long userId, long attachmentId) {
        DownloadItem item = download(userId, attachmentId);
        if (!AttachmentMediaTypes.isSafeInline(item.meta().getContentType())) {
            throw new UnsafeInlineMediaTypeException("인라인 표시를 허용하지 않는 첨부 형식입니다");
        }
        return item;
    }

    public void delete(long userId, long attachmentId) {
        Attachment a = attachments.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("첨부 없음: " + attachmentId));
        Page page = pages.getOwned(a.getPageId());
        spaces.require(userId, page.getSpaceId(), WikiAction.EDIT);
        attachments.delete(a);
        storage.deleteAfterCommit(a.getStorageBackend(), a.getStorageBucket(),
                a.getStorageKey(), a.getStorageVersion());
        events.afterCommit(WikiEvents.attachmentDeleted(userId, a, page.getSpaceId()));
    }

    public record DownloadItem(Attachment meta, Resource resource) {}
}
