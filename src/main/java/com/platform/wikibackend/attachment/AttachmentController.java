package com.platform.wikibackend.attachment;

import com.platform.wikibackend.attachment.dto.AttachmentResponse;
import com.platform.wikibackend.attachment.dto.ConfirmAttachmentsRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.platform.wikibackend.space.SpaceController.userId;

@RestController
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService service;

    @PostMapping("/api/wiki/pages/{pageId}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse upload(@AuthenticationPrincipal Jwt jwt, @PathVariable Long pageId,
                                     @RequestParam("file") MultipartFile file,
                                     @RequestParam(name = "pending", defaultValue = "false") boolean pending) {
        return service.upload(userId(jwt), pageId, file, pending);
    }

    /** 페이지 저장 뒤 본문에 남은 에디터 업로드를 장기 보존 대상으로 확정한다. */
    @PostMapping("/api/wiki/pages/{pageId}/attachments/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable Long pageId,
                        @RequestBody ConfirmAttachmentsRequest request) {
        service.confirm(userId(jwt), pageId, request.attachmentIds());
    }

    @GetMapping("/api/wiki/pages/{pageId}/attachments")
    public List<AttachmentResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable Long pageId) {
        return service.list(userId(jwt), pageId);
    }

    /** Content-Disposition attachment 고정 — 브라우저 인라인 실행(XSS) 차단(스펙). */
    @GetMapping("/api/wiki/attachments/{id}")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        AttachmentService.DownloadItem item = service.download(userId(jwt), id);
        String encoded = URLEncoder.encode(item.meta().getFilename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(item.meta().getContentType()))
                .contentLength(item.meta().getSizeBytes())
                .body(item.resource());
    }

    /** 권한 확인 후 안전한 래스터 이미지만 인라인으로 제공한다(SVG/HTML 실행 차단). */
    @GetMapping("/api/wiki/attachments/{id}/inline")
    public ResponseEntity<Resource> inline(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        AttachmentService.DownloadItem item = service.inline(userId(jwt), id);
        String encoded = URLEncoder.encode(item.meta().getFilename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60, no-transform")
                .header("X-Content-Type-Options", "nosniff")
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .contentType(MediaType.parseMediaType(item.meta().getContentType()))
                .contentLength(item.meta().getSizeBytes())
                .body(item.resource());
    }

    @DeleteMapping("/api/wiki/attachments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        service.delete(userId(jwt), id);
    }
}
