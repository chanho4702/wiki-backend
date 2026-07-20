package com.platform.wikibackend.attachment;

import com.platform.wikibackend.attachment.dto.AttachmentResponse;
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
                                     @RequestParam("file") MultipartFile file) {
        return service.upload(userId(jwt), pageId, file);
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
                .contentType(MediaType.parseMediaType(item.meta().getContentType()))
                .body(item.resource());
    }

    @DeleteMapping("/api/wiki/attachments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        service.delete(userId(jwt), id);
    }
}
