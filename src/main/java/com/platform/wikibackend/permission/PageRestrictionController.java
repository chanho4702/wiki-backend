package com.platform.wikibackend.permission;

import com.platform.wikibackend.permission.dto.PageRestrictionsResponse;
import com.platform.wikibackend.permission.dto.RestrictionPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.platform.wikibackend.space.SpaceController.userId;

@RestController
@RequiredArgsConstructor
public class PageRestrictionController {

    private final PageRestrictionService service;

    @GetMapping("/api/wiki/pages/{id}/restrictions")
    public PageRestrictionsResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return service.get(userId(jwt), id);
    }

    /** 전체 교체 — 빈 배열 = 해당 타입 제한 없음. */
    @PutMapping("/api/wiki/pages/{id}/restrictions")
    public PageRestrictionsResponse replace(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                            @RequestBody ReplaceRequest req) {
        return service.replace(userId(jwt), id,
                req.view() == null ? List.of() : req.view(),
                req.edit() == null ? List.of() : req.edit());
    }

    public record ReplaceRequest(List<RestrictionPrincipal> view, List<RestrictionPrincipal> edit) {
    }
}
