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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import static com.platform.wikibackend.space.SpaceController.userId;

@Tag(name = "Attachments", description = "페이지 첨부 파일의 업로드·목록·내려받기와 버전 관리.")
@RestController
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService service;

    @Operation(summary = "페이지에 첨부 파일을 올린다")
    @PostMapping("/api/wiki/pages/{pageId}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse upload(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "페이지 ID") @PathVariable Long pageId,
                                     @Parameter(description = "올릴 파일(multipart/form-data)")
                                     @RequestParam("file") MultipartFile file,
                                     @Parameter(description = "본문 저장 전 에디터가 먼저 올리는 임시 첨부. confirm 전까지 정리 대상이다")
                                     @RequestParam(name = "pending", defaultValue = "false") boolean pending) {
        return service.upload(userId(jwt), pageId, file, pending);
    }

    /** 페이지 저장 뒤 본문에 남은 에디터 업로드를 장기 보존 대상으로 확정한다. */
    @Operation(summary = "에디터가 먼저 올린 임시 첨부를 본문 저장 뒤 확정한다")
    @PostMapping("/api/wiki/pages/{pageId}/attachments/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "페이지 ID") @PathVariable Long pageId,
                        @RequestBody ConfirmAttachmentsRequest request) {
        service.confirm(userId(jwt), pageId, request.attachmentIds());
    }

    @Operation(summary = "페이지의 첨부 목록을 조회한다")
    @GetMapping("/api/wiki/pages/{pageId}/attachments")
    public List<AttachmentResponse> list(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "페이지 ID") @PathVariable Long pageId) {
        return service.list(userId(jwt), pageId);
    }

    /** Content-Disposition attachment 고정 — 브라우저 인라인 실행(XSS) 차단(스펙). */
    @Operation(summary = "첨부를 내려받는다 — 브라우저 인라인 실행은 막는다")
    @ApiResponse(responseCode = "200", description = "첨부 파일 내용. Content-Type은 첨부의 실제 타입이다",
            content = @Content(mediaType = "application/octet-stream",
                    schema = @Schema(type = "string", format = "binary")))
    @GetMapping("/api/wiki/attachments/{id}")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "첨부 ID") @PathVariable Long id) {
        AttachmentService.DownloadItem item = service.download(userId(jwt), id);
        String encoded = URLEncoder.encode(item.meta().getFilename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(item.contentType()))
                .contentLength(item.sizeBytes())
                .body(item.resource());
    }

    /** 지난 버전 목록 — 최신이 먼저. 현재 내용은 목록에 없다(첨부 자체가 곧 현재다). */
    @Operation(summary = "첨부의 지난 버전 목록을 최신순으로 조회한다")
    @GetMapping("/api/wiki/attachments/{id}/versions")
    public List<com.platform.wikibackend.attachment.dto.AttachmentVersionResponse> versions(
            @AuthenticationPrincipal Jwt jwt, @Parameter(description = "첨부 ID") @PathVariable Long id) {
        return service.versions(userId(jwt), id);
    }

    /** 지난 버전 내려받기 — 미리보기는 주지 않는다(옛 파일을 문서에 다시 심을 이유가 없다). */
    @Operation(summary = "첨부의 지난 버전을 내려받는다")
    @ApiResponse(responseCode = "200", description = "그 버전의 파일 내용",
            content = @Content(mediaType = "application/octet-stream",
                    schema = @Schema(type = "string", format = "binary")))
    @GetMapping("/api/wiki/attachments/{id}/versions/{version}")
    public ResponseEntity<Resource> downloadVersion(@AuthenticationPrincipal Jwt jwt,
                                                    @Parameter(description = "첨부 ID") @PathVariable Long id,
                                                    @Parameter(description = "내려받을 지난 버전 번호(1부터)")
                                                    @PathVariable int version) {
        AttachmentService.DownloadItem item = service.downloadVersion(userId(jwt), id, version);
        String encoded = URLEncoder.encode(item.meta().getFilename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(item.contentType()))
                .contentLength(item.sizeBytes())
                .body(item.resource());
    }

    /** 지난 버전을 현재로 되돌린다 — 되돌린 것도 새 버전으로 쌓인다. */
    @Operation(summary = "지난 버전을 현재 첨부로 되돌린다 — 되돌린 것도 새 버전으로 쌓인다")
    @PostMapping("/api/wiki/attachments/{id}/versions/{version}/restore")
    public com.platform.wikibackend.attachment.dto.AttachmentResponse restoreVersion(
            @AuthenticationPrincipal Jwt jwt, @Parameter(description = "첨부 ID") @PathVariable Long id,
            @Parameter(description = "현재로 되돌릴 지난 버전 번호") @PathVariable int version) {
        return service.restoreVersion(userId(jwt), id, version);
    }

    /** 권한 확인 후 안전한 타입만 인라인으로 제공한다(SVG/HTML 실행 차단). */
    @Operation(summary = "안전한 타입의 첨부만 인라인으로 표시한다")
    @ApiResponse(responseCode = "200", description = "첨부 파일 내용. 안전한 타입만 인라인으로 나간다",
            content = @Content(mediaType = "application/octet-stream",
                    schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "415", description = "허용되지 않는 미디어 타입",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(ref = "#/components/schemas/PlatformError")))
    @GetMapping("/api/wiki/attachments/{id}/inline")
    public ResponseEntity<Resource> inline(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "첨부 ID") @PathVariable Long id) {
        AttachmentService.DownloadItem item = service.inline(userId(jwt), id);
        String encoded = URLEncoder.encode(item.meta().getFilename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60, no-transform")
                .header("X-Content-Type-Options", "nosniff")
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .contentType(MediaType.parseMediaType(item.contentType()))
                .contentLength(item.sizeBytes())
                .body(item.resource());
    }

    @Operation(summary = "첨부를 삭제한다")
    @DeleteMapping("/api/wiki/attachments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "첨부 ID") @PathVariable Long id) {
        service.delete(userId(jwt), id);
    }
}
