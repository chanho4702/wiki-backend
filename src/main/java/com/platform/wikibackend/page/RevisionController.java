package com.platform.wikibackend.page;

import com.platform.wikibackend.page.dto.PageResponse;
import com.platform.wikibackend.page.dto.RevisionMeta;
import com.platform.wikibackend.page.dto.RevisionResponse;
import com.platform.wikibackend.page.dto.RevisionRestoreRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import static com.platform.wikibackend.space.SpaceController.userId;

@Tag(name = "Revisions", description = "페이지 버전 이력 조회와 복원.")
@RestController
@RequestMapping("/api/wiki/pages/{pageId}/revisions")
@RequiredArgsConstructor
public class RevisionController {

    private final PageService pages;

    @Operation(summary = "페이지의 버전 목록을 조회한다")
    @GetMapping
    public List<RevisionMeta> list(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "페이지 ID") @PathVariable Long pageId) {
        return pages.listRevisions(userId(jwt), pageId);
    }

    @Operation(summary = "특정 버전의 본문을 조회한다")
    @GetMapping("/{version}")
    public RevisionResponse get(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "페이지 ID") @PathVariable Long pageId,
                                @Parameter(description = "조회할 버전 번호(1부터)") @PathVariable Integer version) {
        return pages.getRevision(userId(jwt), pageId, version);
    }

    /**
     * 본문은 통째로 선택이다(`required = false`). 본문 없이 부르는 클라이언트가 이미 있고, 기본
     * 요약("v{n} 버전으로 복원")만으로도 복원은 읽힌다 — 요약을 실어 보내면 그것이 이긴다.
     */
    @Operation(summary = "과거 버전 내용으로 되돌린다 — 되돌린 것도 새 버전으로 쌓인다")
    @PostMapping("/{version}/restore")
    public PageResponse restore(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "페이지 ID") @PathVariable Long pageId,
                                @Parameter(description = "되돌릴 버전 번호") @PathVariable Integer version,
                                @RequestBody(required = false) @Valid RevisionRestoreRequest req) {
        return pages.restore(userId(jwt), pageId, version, req == null ? null : req.changeNote());
    }
}
