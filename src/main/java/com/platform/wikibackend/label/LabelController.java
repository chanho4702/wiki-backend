package com.platform.wikibackend.label;

import com.platform.wikibackend.page.dto.PageTreeItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Schema;

import static com.platform.wikibackend.space.SpaceController.userId;

/** 라벨·백링크(W21-2). */
@Tag(name = "Labels", description = "페이지 라벨과 백링크.")
@RestController
@RequiredArgsConstructor
public class LabelController {

    private final LabelService labels;

    @Operation(summary = "페이지에 붙은 라벨을 조회한다")
    @GetMapping("/api/wiki/pages/{pageId}/labels")
    public List<String> list(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "페이지 ID") @PathVariable Long pageId) {
        return labels.list(userId(jwt), pageId);
    }

    @Operation(summary = "페이지의 라벨을 통째로 교체한다")
    @PutMapping("/api/wiki/pages/{pageId}/labels")
    public List<String> replace(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "페이지 ID") @PathVariable Long pageId,
                                @Valid @RequestBody LabelsRequest req) {
        return labels.replace(userId(jwt), pageId, req.labels());
    }

    @Operation(summary = "이 페이지를 본문에서 링크한 문서를 조회한다")
    @GetMapping("/api/wiki/pages/{pageId}/backlinks")
    public List<PageTreeItem> backlinks(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "페이지 ID") @PathVariable Long pageId) {
        return labels.backlinks(userId(jwt), pageId);
    }

    /**
     * 접근 가능한 스페이스 전체의 라벨 후보 — 검색 화면의 라벨 필터 자동완성.
     * 스페이스를 가로지르므로 스페이스 경로 아래에 두지 않는다.
     */
    @Operation(summary = "접근 가능한 스페이스 전체에서 라벨 후보를 찾는다")
    @GetMapping("/api/wiki/labels")
    public List<LabelService.LabelCountResponse> suggest(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "라벨 이름 앞부분. 비우면 많이 쓰인 순으로 돌려준다")
            @RequestParam(name = "q", required = false) String q) {
        return labels.suggest(userId(jwt), q);
    }

    @Operation(summary = "스페이스에서 쓰인 라벨을 사용 횟수와 함께 조회한다")
    @GetMapping("/api/wiki/spaces/{spaceId}/labels")
    public List<LabelService.LabelCountResponse> inSpace(@AuthenticationPrincipal Jwt jwt,
                                                        @Parameter(description = "스페이스 ID") @PathVariable Long spaceId) {
        return labels.listInSpace(userId(jwt), spaceId);
    }

    @Operation(summary = "그 라벨이 붙은 페이지를 조회한다")
    @GetMapping("/api/wiki/spaces/{spaceId}/labels/{name}/pages")
    public List<PageTreeItem> pagesWithLabel(@AuthenticationPrincipal Jwt jwt,
                                             @Parameter(description = "스페이스 ID") @PathVariable Long spaceId,
                                             @Parameter(description = "라벨 이름") @PathVariable String name) {
        return labels.pagesWithLabel(userId(jwt), spaceId, name);
    }

    @Schema(description = "페이지 라벨 전체 교체 요청")
    public record LabelsRequest(
            @Schema(description = "이 페이지에 남길 라벨 전체. 빈 배열이면 라벨을 모두 뗀다",
                    example = "[\"배포\", \"운영\"]")
            @NotNull List<String> labels) {
    }
}
