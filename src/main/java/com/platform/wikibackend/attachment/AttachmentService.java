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
import java.util.LinkedHashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class AttachmentService {

    private final AttachmentRepository attachments;
    private final PageService pages;
    private final SpaceService spaces;
    private final com.platform.wikibackend.permission.EffectivePermissionService effective;
    private final AttachmentStorageRouter storage;
    private final EventRelay events;

    public AttachmentResponse upload(long userId, long pageId, MultipartFile file) {
        return upload(userId, pageId, file, false);
    }

    public AttachmentResponse upload(long userId, long pageId, MultipartFile file, boolean pending) {
        Page page = pages.getOwned(pageId);
        spaces.require(userId, page.getSpaceId(), WikiAction.EDIT);
        effective.requireEdit(userId, page);
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
            AttachmentLifecycleStatus lifecycleStatus = pending
                    ? AttachmentLifecycleStatus.PENDING
                    : AttachmentLifecycleStatus.CONFIRMED;
            Attachment saved = attachments.save(
                    Attachment.of(pageId, filename, contentType, file.getSize(), stored, checksum, userId,
                            lifecycleStatus));
            if (!pending) {
                events.afterCommit(WikiEvents.attachmentAdded(userId, saved, page.getSpaceId()));
            }
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
        effective.requireView(userId, page);
        return attachments.findByPageId(pageId).stream().map(AttachmentResponse::from).toList();
    }

    /** 다운로드용 — [메타, 리소스] 쌍. */
    @Transactional(readOnly = true)
    public DownloadItem download(long userId, long attachmentId) {
        Attachment a = attachments.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("첨부 없음: " + attachmentId));
        Page page = pages.getOwned(a.getPageId());
        spaces.require(userId, page.getSpaceId(), WikiAction.VIEW);
        effective.requireView(userId, page);
        return new DownloadItem(a, storage.open(a.getStorageBackend(), a.getStorageBucket(),
                a.getStorageKey(), a.getStorageVersion()));
    }

    /**
     * 페이지 저장과 별도 요청이므로 서버의 최신 본문을 다시 검사한다. 클라이언트가 임의 ID를
     * 확정하거나 저장 실패한 이미지를 장기 보존 상태로 바꾸지 못한다.
     */
    public void confirm(long userId, long pageId, List<Long> attachmentIds) {
        Page page = pages.getOwned(pageId);
        spaces.require(userId, page.getSpaceId(), WikiAction.EDIT);
        effective.requireEdit(userId, page);
        if (attachmentIds == null || attachmentIds.isEmpty()) return;

        Set<Long> uniqueIds = new LinkedHashSet<>(attachmentIds);
        List<Attachment> found = attachments.findAllById(uniqueIds);
        if (found.size() != uniqueIds.size()) {
            throw new NotFoundException("확정할 첨부 중 존재하지 않는 항목이 있습니다");
        }

        for (Attachment attachment : found) {
            if (!attachment.getPageId().equals(pageId)) {
                throw new IllegalArgumentException("다른 페이지의 첨부는 확정할 수 없습니다");
            }
            String durableUrl = AttachmentReferences.inlineUrl(attachment.getId());
            if (!page.getContent().contains(durableUrl)) {
                throw new IllegalArgumentException("본문에서 참조하지 않는 첨부는 확정할 수 없습니다: "
                        + attachment.getId());
            }
        }

        for (Attachment attachment : found) {
            if (attachment.getLifecycleStatus() == AttachmentLifecycleStatus.PENDING) {
                attachment.confirm();
                events.afterCommit(WikiEvents.attachmentAdded(userId, attachment, page.getSpaceId()));
            }
        }
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
        effective.requireEdit(userId, page);
        attachments.delete(a);
        storage.deleteAfterCommit(a.getStorageBackend(), a.getStorageBucket(),
                a.getStorageKey(), a.getStorageVersion());
        // PENDING은 아직 AttachmentAdded를 발행하지 않은 임시 객체다. 취소 정리에 Deleted만 내보내면
        // 검색 소비자가 보지 못한 엔티티의 삭제 이벤트를 받게 되므로 확정 첨부만 발행한다.
        if (a.getLifecycleStatus() == AttachmentLifecycleStatus.CONFIRMED) {
            events.afterCommit(WikiEvents.attachmentDeleted(userId, a, page.getSpaceId()));
        }
    }

    public record DownloadItem(Attachment meta, Resource resource) {}
}
