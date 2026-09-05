package com.platform.wikibackend.permission;

import com.platform.wikibackend.permission.dto.PageRestrictionsResponse;
import com.platform.wikibackend.permission.dto.RestrictionPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;

import static com.platform.wikibackend.space.SpaceController.userId;

@Tag(name = "Page Restrictions", description = "페이지 단위 열람·편집 제한.")
@RestController
@RequiredArgsConstructor
public class PageRestrictionController {

    private final PageRestrictionService service;

    @Operation(summary = "페이지에 걸린 열람·편집 제한을 조회한다")
    @GetMapping("/api/wiki/pages/{id}/restrictions")
    public PageRestrictionsResponse get(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "페이지 ID") @PathVariable Long id) {
        return service.get(userId(jwt), id);
    }

    /** 전체 교체 — 빈 배열 = 해당 타입 제한 없음. */
    @Operation(summary = "페이지 제한을 통째로 교체한다 — 빈 배열이면 그 타입은 제한 없음")
    @PutMapping("/api/wiki/pages/{id}/restrictions")
    public PageRestrictionsResponse replace(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "페이지 ID") @PathVariable Long id,
                                            @RequestBody ReplaceRequest req) {
        return service.replace(userId(jwt), id,
                req.view() == null ? List.of() : req.view(),
                req.edit() == null ? List.of() : req.edit());
    }

    public record ReplaceRequest(List<RestrictionPrincipal> view, List<RestrictionPrincipal> edit) {
    }
}
