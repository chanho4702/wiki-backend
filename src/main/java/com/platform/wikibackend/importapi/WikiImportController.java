package com.platform.wikibackend.importapi;

import com.platform.wikibackend.config.InternalTokenFilter;
import com.platform.wikibackend.importapi.dto.WikiImportRequests;
import com.platform.wikibackend.importapi.dto.WikiImportResponses;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 이관 엔진(migration-service)이 부르는 내부 쓰기 API(W29 X1, 설계 §2).
 *
 * `/api/wiki/**`가 아니라 `/internal/**`인 것이 계약의 일부다 — 게이트웨이와 nginx가 이 접두를
 * 라우팅하지 않아 브라우저에서는 존재하지 않는 경로이고, 인증도 사용자 JWT가 아니라 공유 비밀
 * ({@link InternalTokenFilter})이다. 공개 REST에 이관용 우회로를 뚫지 않기 위한 분리다.
 *
 * OpenAPI 문서에서는 감춘다(@Hidden) — 수집한 스펙은 프론트가 보는 공개 계약이고, 여기에
 * 내부 경로가 실리면 "쓸 수 있는 API"로 읽힌다.
 */
@Hidden
@RestController
@RequestMapping("/internal/wiki/import")
@RequiredArgsConstructor
public class WikiImportController {

    private final WikiImportService service;

    @PostMapping("/pages")
    public WikiImportResponses.PageWritten createPage(@RequestHeader(name = InternalTokenFilter.ACTOR_HEADER,
                                                              required = false) String actor,
                                                      @RequestBody WikiImportRequests.CreatePage req) {
        return service.createPage(actorId(actor), req);
    }

    @PutMapping("/pages/{pageId}")
    public WikiImportResponses.PageWritten reimportPage(@RequestHeader(name = InternalTokenFilter.ACTOR_HEADER,
                                                                required = false) String actor,
                                                        @PathVariable long pageId,
                                                        @RequestBody WikiImportRequests.ReimportPage req) {
        return service.reimportPage(actorId(actor), pageId, req);
    }

    @PutMapping("/pages/{pageId}/content")
    public WikiImportResponses.ContentWritten rewriteContent(@RequestHeader(name = InternalTokenFilter.ACTOR_HEADER,
                                                                     required = false) String actor,
                                                             @PathVariable long pageId,
                                                             @RequestBody WikiImportRequests.RewriteContent req) {
        return service.rewriteContent(actorId(actor), pageId, req);
    }

    @PutMapping("/pages/{pageId}/order")
    public WikiImportResponses.OrderWritten reorder(@RequestHeader(name = InternalTokenFilter.ACTOR_HEADER,
                                                            required = false) String actor,
                                                    @PathVariable long pageId,
                                                    @RequestBody WikiImportRequests.Reorder req) {
        actorId(actor); // 헤더 검증은 쓰기 경로에서 예외 없이 건다 — 감사 폴백이 없는 요청도 마찬가지다.
        return service.reorder(pageId, req);
    }

    /**
     * 첨부 한 건. multipart 상한은 일반 업로드와 같은 `spring.servlet.multipart` 설정을 쓴다 —
     * 엔진이 원본에서 받아 그대로 올리므로 위키가 받을 수 있는 크기가 곧 이관 상한이다.
     */
    @PostMapping("/pages/{pageId}/attachments")
    public WikiImportResponses.AttachmentRegistered registerAttachment(
            @RequestHeader(name = InternalTokenFilter.ACTOR_HEADER, required = false) String actor,
            @PathVariable long pageId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "filename", required = false) String filename,
            @RequestParam(name = "contentType", required = false) String contentType,
            @RequestParam(name = "checksum", required = false) String checksum,
            @RequestParam(name = "sourceVersion", required = false) String sourceVersion) {
        // contentType·sourceVersion은 받되 쓰지 않는다. 형식은 우리가 바이트에서 다시 판정하고
        // (인라인 허용 여부가 거기 걸린다), 원본 버전은 엔진의 원장에만 뜻이 있다.
        return service.registerAttachment(actorId(actor), pageId, file, filename, checksum);
    }

    @PostMapping("/pages/{pageId}/comments")
    public WikiImportResponses.CommentWritten createComment(@RequestHeader(name = InternalTokenFilter.ACTOR_HEADER,
                                                                    required = false) String actor,
                                                            @PathVariable long pageId,
                                                            @RequestBody WikiImportRequests.CreateComment req) {
        return service.createComment(actorId(actor), pageId, req);
    }

    @GetMapping("/comments/{commentId}")
    public WikiImportResponses.CommentView comment(@PathVariable long commentId) {
        return service.comment(commentId);
    }

    @PutMapping("/pages/{pageId}/restrictions")
    public ResponseEntity<Void> replaceRestrictions(@RequestHeader(name = InternalTokenFilter.ACTOR_HEADER,
                                                            required = false) String actor,
                                                    @PathVariable long pageId,
                                                    @RequestBody WikiImportRequests.ReplaceRestrictions req) {
        service.replaceRestrictions(actorId(actor), pageId, req);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/pages/{pageId}")
    public WikiImportResponses.PageView page(@PathVariable long pageId) {
        return service.page(pageId);
    }

    @GetMapping("/spaces/{spaceId}/pages")
    public WikiImportResponses.PageMatches pagesByTitle(@PathVariable long spaceId,
                                                        @RequestParam(name = "title", required = false) String title) {
        return service.pagesByTitle(spaceId, title);
    }

    @GetMapping("/spaces/{spaceId}")
    public WikiImportResponses.SpaceView space(@PathVariable long spaceId) {
        return service.space(spaceId);
    }

    /**
     * 잡 요청자 id. 필터가 아니라 여기서 검증하는 이유: 토큰은 맞는데 헤더가 빠진 요청은 인증
     * 실패(403)가 아니라 잘못된 요청(400)이고, 그 구분이 엔진 쪽 디버깅에서 전부다.
     * 읽기 전용 조회는 요구하지 않는다 — 남길 기록도 폴백할 작성자도 없다.
     */
    private static long actorId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(InternalTokenFilter.ACTOR_HEADER + " 헤더가 필요합니다");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(InternalTokenFilter.ACTOR_HEADER + " 헤더는 숫자여야 합니다: " + raw);
        }
    }
}
