package com.platform.wikibackend.export;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import static com.platform.wikibackend.space.SpaceController.userId;

/** 페이지 PDF 내보내기(W26). 첨부 다운로드와 같은 Content-Disposition 규약. */
@Tag(name = "Export", description = "페이지 PDF 내보내기.")
@RestController
@RequiredArgsConstructor
public class PdfExportController {

    private final PdfExportService service;

    @Operation(summary = "페이지를 PDF로 내보낸다")
    @ApiResponse(responseCode = "200", description = "PDF 파일. Content-Disposition은 항상 attachment다",
            content = @Content(mediaType = "application/pdf",
                    schema = @Schema(type = "string", format = "binary")))
    @GetMapping("/api/wiki/pages/{id}/export.pdf")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                         @Parameter(description = "하위 페이지까지 한 PDF에 이어 붙인다")
                                         @RequestParam(defaultValue = "false") boolean includeChildren) {
        PdfExportService.Export export = service.export(userId(jwt), id, includeChildren);
        String encoded = URLEncoder.encode(export.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_PDF)
                .body(export.bytes());
    }
}
