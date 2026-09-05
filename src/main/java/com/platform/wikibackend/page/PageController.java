package com.platform.wikibackend.page;

import com.platform.wikibackend.page.dto.PageCreateRequest;
import com.platform.wikibackend.page.dto.PageIconRequest;
import com.platform.wikibackend.page.dto.PageMoveRequest;
import com.platform.wikibackend.page.dto.CollaborationDraftCommitRequest;
import com.platform.wikibackend.page.dto.CollaborationDraftCommitResponse;
import com.platform.wikibackend.page.dto.PageResponse;
import com.platform.wikibackend.page.dto.PageUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import static com.platform.wikibackend.space.SpaceController.userId;

@Tag(name = "Pages", description = "페이지 생성·조회·수정·이동·복사·삭제.")
@RestController
@RequiredArgsConstructor
public class PageController {

    private final PageService pages;

    @Operation(summary = "페이지를 만든다")
    @PostMapping("/api/wiki/pages")
    @ResponseStatus(HttpStatus.CREATED)
    public PageResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PageCreateRequest req) {
        return pages.create(userId(jwt), req);
    }

    @Operation(summary = "페이지 본문과 메타데이터를 조회한다")
    @GetMapping("/api/wiki/pages/{id}")
    public PageResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return pages.get(userId(jwt), id);
    }

    @Operation(summary = "페이지를 수정한다 — expectedVersion이 현재 버전과 다르면 409")
    @PutMapping("/api/wiki/pages/{id}")
    public PageResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                               @Valid @RequestBody PageUpdateRequest req) {
        return pages.update(userId(jwt), id, req);
    }

    @Operation(summary = "공동 편집 초안을 정본으로 확정한다")
    @PutMapping("/api/wiki/pages/{id}/collaboration-draft")
    public CollaborationDraftCommitResponse commitCollaborationDraft(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @Valid @RequestBody CollaborationDraftCommitRequest req) {
        return pages.commitCollaborationDraft(userId(jwt), id, req);
    }

    @Operation(summary = "페이지를 다른 부모·순서·스페이스로 옮긴다")
    @PostMapping("/api/wiki/pages/{id}/move")
    public PageResponse move(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                             @Valid @RequestBody PageMoveRequest req) {
        return pages.move(userId(jwt), id, req);
    }

    /** 본문은 선택 — 없으면 단일 페이지 복사(기존 계약 그대로). */
    @Operation(summary = "페이지를 복사한다 — 옵션을 비우면 그 페이지 하나만")
    @PostMapping("/api/wiki/pages/{id}/copy")
    @ResponseStatus(HttpStatus.CREATED)
    public PageResponse copy(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                             @RequestBody(required = false) com.platform.wikibackend.page.dto.CopyRequest req) {
        return pages.copy(userId(jwt), id,
                req == null ? new com.platform.wikibackend.page.dto.CopyRequest(null, null) : req);
    }

    @Operation(summary = "페이지 아이콘을 지정한다")
    @PutMapping("/api/wiki/pages/{id}/icon")
    public PageResponse setIcon(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                @Valid @RequestBody PageIconRequest req) {
        return pages.setIcon(userId(jwt), id, req.icon());
    }

    @Operation(summary = "페이지를 다른 사용자에게 공유해 알림을 보낸다")
    @PostMapping("/api/wiki/pages/{id}/share")
    public com.platform.wikibackend.page.dto.ShareResponse share(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @jakarta.validation.Valid @RequestBody com.platform.wikibackend.page.dto.ShareRequest req) {
        return pages.share(userId(jwt), id, req);
    }

    @Operation(summary = "페이지 조회수를 올리고 최근 방문 기록에 남긴다")
    @PostMapping("/api/wiki/pages/{id}/views")
    public java.util.Map<String, Long> recordView(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return java.util.Map.of("views", pages.recordView(userId(jwt), id));
    }

    /** 소유자 지정·해제(W27-5) — 본문 {"ownerId": 3} 또는 {"ownerId": null}. EDIT 권한. */
    @Operation(summary = "페이지 소유자를 지정하거나 해제한다")
    @PutMapping("/api/wiki/pages/{id}/owner")
    public PageResponse setOwner(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                 @RequestBody com.platform.wikibackend.page.dto.PageOwnerRequest req) {
        return pages.setOwner(userId(jwt), id, req.ownerId());
    }

    /** 검증(W27-5) — 본문 {"verifiedUntil": "2026-12-03"} 또는 {} (기본 90일). EDIT 권한. */
    @Operation(summary = "페이지를 검증 완료로 표시한다 — 기한을 비우면 90일")
    @PutMapping("/api/wiki/pages/{id}/verification")
    public PageResponse verify(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                               @RequestBody(required = false) com.platform.wikibackend.page.dto.PageVerificationRequest req) {
        return pages.verify(userId(jwt), id, req == null ? null : req.verifiedUntil());
    }

    @Operation(summary = "페이지 검증 표시를 해제한다")
    @DeleteMapping("/api/wiki/pages/{id}/verification")
    public PageResponse unverify(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return pages.unverify(userId(jwt), id);
    }

    @Operation(summary = "초안 페이지를 게시한다")
    @PostMapping("/api/wiki/pages/{id}/publish")
    public PageResponse publish(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return pages.publish(userId(jwt), id);
    }

    /**
     * children=promote|cascade — 자식이 있을 때만 의미가 있다. 미지정이면 자식이 있는 경우 409.
     * (Spring 기본 enum 변환은 대소문자를 구분해 소문자 값을 거부하므로 문자열로 받아 변환한다.)
     */
    @Operation(summary = "페이지를 휴지통으로 보낸다")
    @DeleteMapping("/api/wiki/pages/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                       @Parameter(description = "자식이 있을 때의 처리 — promote(끌어올림) 또는 cascade(함께 삭제). 미지정이면 자식이 있을 때 409")
                       @RequestParam(required = false) String children) {
        pages.delete(userId(jwt), id, ChildrenPolicy.from(children));
    }
}
