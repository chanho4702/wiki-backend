package com.platform.wikibackend.page;

import com.platform.wikibackend.page.dto.PageNode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import static com.platform.wikibackend.space.SpaceController.userId;

/**
 * 지연 트리 API(2026-08-28). 기존 `GET /spaces/{id}/pages`(전량)는 그대로 두고 추가한다 —
 * 프론트가 화면 단위로 옮겨가는 동안 두 경로가 공존해야 한다.
 */
@Tag(name = "Page Tree", description = "스페이스 페이지 트리의 지연 조회와 경로 탐색.")
@RestController
@RequiredArgsConstructor
public class TreeController {

    private final TreeService tree;

    /** parentId 생략 = 루트 목록. */
    @Operation(summary = "부모 아래의 자식 페이지를 조회한다 — parentId를 비우면 루트")
    @GetMapping("/api/wiki/spaces/{spaceId}/pages/children")
    public List<PageNode> children(@AuthenticationPrincipal Jwt jwt, @PathVariable Long spaceId,
                                   @Parameter(description = "부모 페이지 ID. 비우면 스페이스 루트")
                                   @RequestParam(required = false) Long parentId) {
        return tree.children(userId(jwt), spaceId, parentId);
    }

    @Operation(summary = "제목으로 페이지를 찾는다 — 위키 링크 해석용")
    @GetMapping("/api/wiki/spaces/{spaceId}/pages/lookup")
    public List<PageNode> lookup(@AuthenticationPrincipal Jwt jwt, @PathVariable Long spaceId,
                                 @Parameter(description = "찾을 페이지 제목. 여러 번 줄 수 있다") @RequestParam List<String> title) {
        return tree.lookupByTitles(userId(jwt), spaceId, title);
    }

    @Operation(summary = "최근 수정된 페이지를 조회한다")
    @GetMapping("/api/wiki/spaces/{spaceId}/pages/recent")
    public List<PageNode> recent(@AuthenticationPrincipal Jwt jwt, @PathVariable Long spaceId,
                                 @Parameter(description = "최대 건수") @RequestParam(defaultValue = "8") int limit) {
        return tree.recentlyUpdated(userId(jwt), spaceId, limit);
    }

    @Operation(summary = "ID 목록으로 페이지 노드를 한 번에 조회한다")
    @GetMapping("/api/wiki/spaces/{spaceId}/pages/by-ids")
    public List<PageNode> byIds(@AuthenticationPrincipal Jwt jwt, @PathVariable Long spaceId,
                                @Parameter(description = "조회할 페이지 ID. 여러 번 줄 수 있다") @RequestParam List<Long> id) {
        return tree.byIds(userId(jwt), spaceId, id);
    }

    @Operation(summary = "스페이스 안에서 제목으로 페이지를 검색한다")
    @GetMapping("/api/wiki/spaces/{spaceId}/pages/search")
    public List<PageNode> search(@AuthenticationPrincipal Jwt jwt, @PathVariable Long spaceId,
                                 @Parameter(description = "제목에서 찾을 말") @RequestParam String q) {
        return tree.searchByTitle(userId(jwt), spaceId, q);
    }

    /**
     * 여러 페이지의 조상 경로 — 검색 결과가 "어디에 있는 문서인지"를 그린다.
     *
     * 스페이스를 가로지르므로 스페이스 경로 아래에 두지 않는다. 검색 결과 한 페이지(최대 100건)를
     * 한 번에 물어보는 용도라 GET 쿼리 파라미터로 받는다.
     */
    @Operation(summary = "여러 페이지의 조상 경로를 한 번에 조회한다")
    @GetMapping("/api/wiki/pages/paths")
    public List<com.platform.wikibackend.page.dto.PagePath> paths(
            @AuthenticationPrincipal Jwt jwt, @Parameter(description = "경로를 구할 페이지 ID. 한 번에 최대 100건")
                                              @RequestParam("id") List<Long> ids) {
        return tree.paths(userId(jwt), ids);
    }

    @Operation(summary = "페이지의 조상을 루트부터 조회한다")
    @GetMapping("/api/wiki/pages/{pageId}/ancestors")
    public List<PageNode> ancestors(@AuthenticationPrincipal Jwt jwt, @PathVariable Long pageId) {
        return tree.ancestors(userId(jwt), pageId);
    }

    @Operation(summary = "페이지의 하위 노드를 전부 조회한다")
    @GetMapping("/api/wiki/pages/{pageId}/descendants")
    public List<PageNode> descendants(@AuthenticationPrincipal Jwt jwt, @PathVariable Long pageId) {
        return tree.descendants(userId(jwt), pageId);
    }
}
