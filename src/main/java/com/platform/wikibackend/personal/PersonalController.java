package com.platform.wikibackend.personal;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import static com.platform.wikibackend.space.SpaceController.userId;

/**
 * 별표·최근 방문 REST(W23) — 스페이스를 가로지르는 사용자 데이터라 스페이스 경로 아래에 두지 않는다.
 *
 * 방문 기록에는 별도 엔드포인트가 없다. 페이지 조회수 증가(`POST /pages/{id}/views`)가 이미
 * 모든 열람에서 도므로 거기서 함께 남긴다 — 왕복을 늘릴 이유가 없다.
 */
@Tag(name = "Personal", description = "개인 별표와 최근 방문 문서.")
@RestController
@RequiredArgsConstructor
public class PersonalController {

    private final PersonalService personal;

    @Operation(summary = "내가 별표한 페이지와 스페이스를 조회한다")
    @GetMapping("/api/wiki/stars")
    public PersonalService.StarsResponse stars(@AuthenticationPrincipal Jwt jwt) {
        return personal.stars(userId(jwt));
    }

    @Operation(summary = "페이지에 별표를 단다")
    @PutMapping("/api/wiki/pages/{pageId}/star")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void starPage(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "페이지 ID") @PathVariable Long pageId) {
        personal.starPage(userId(jwt), pageId);
    }

    @Operation(summary = "페이지 별표를 뗀다")
    @DeleteMapping("/api/wiki/pages/{pageId}/star")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unstarPage(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "페이지 ID") @PathVariable Long pageId) {
        personal.unstarPage(userId(jwt), pageId);
    }

    @Operation(summary = "스페이스에 별표를 단다")
    @PutMapping("/api/wiki/spaces/{spaceId}/star")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void starSpace(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "스페이스 ID") @PathVariable Long spaceId) {
        personal.starSpace(userId(jwt), spaceId);
    }

    @Operation(summary = "스페이스 별표를 뗀다")
    @DeleteMapping("/api/wiki/spaces/{spaceId}/star")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unstarSpace(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "스페이스 ID") @PathVariable Long spaceId) {
        personal.unstarSpace(userId(jwt), spaceId);
    }

    @Operation(summary = "내가 최근에 본 페이지를 조회한다")
    @GetMapping("/api/wiki/recent")
    public List<PersonalService.StarredPage> recent(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "최대 건수") @RequestParam(defaultValue = "10") int limit) {
        return personal.recent(userId(jwt), limit);
    }
}
