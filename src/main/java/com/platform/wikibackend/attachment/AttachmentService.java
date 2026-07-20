package com.platform.wikibackend.attachment;

import com.platform.wikibackend.attachment.dto.AttachmentResponse;
import com.platform.wikibackend.common.NotFoundException;
import com.platform.wikibackend.domain.Attachment;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.event.EventRelay;
import com.platform.wikibackend.event.WikiEvents;
import com.platform.wikibackend.page.PageService;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.space.SpaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AttachmentService {

    private final AttachmentRepository attachments;
    private final PageService pages;
    private final SpaceService spaces;
    private final LocalFileStorage storage;
    private final EventRelay events;

    public AttachmentResponse upload(long userId, long pageId, MultipartFile file) {
        Page page = pages.getOwned(pageId);
        spaces.require(userId, page.getSpaceId(), WikiAction.EDIT);
        String key;
        try {
            key = storage.store(file.getInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 스트림 읽기 실패", e);
        }
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unnamed";
        String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        Attachment saved = attachments.save(
                Attachment.of(pageId, filename, contentType, file.getSize(), key, userId));
        events.afterCommit(WikiEvents.attachmentAdded(userId, saved, page.getSpaceId()));
        return AttachmentResponse.from(saved);
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
        return new DownloadItem(a, storage.open(a.getStorageKey()));
    }

    public void delete(long userId, long attachmentId) {
        Attachment a = attachments.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("첨부 없음: " + attachmentId));
        Page page = pages.getOwned(a.getPageId());
        spaces.require(userId, page.getSpaceId(), WikiAction.EDIT);
        attachments.delete(a);
        if (!storage.delete(a.getStorageKey())) {
            log.warn("첨부 파일 삭제 실패(고아 파일 — 무해): key={}", a.getStorageKey());
        }
    }

    public record DownloadItem(Attachment meta, Resource resource) {}
}
