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

import static com.platform.wikibackend.space.SpaceController.userId;

/**
 * 지연 트리 API(2026-08-28). 기존 `GET /spaces/{id}/pages`(전량)는 그대로 두고 추가한다 —
 * 프론트가 화면 단위로 옮겨가는 동안 두 경로가 공존해야 한다.
 */
@RestController
@RequiredArgsConstructor
public class TreeController {

    private final TreeService tree;

    /** parentId 생략 = 루트 목록. */
    @GetMapping("/api/wiki/spaces/{spaceId}/pages/children")
    public List<PageNode> children(@AuthenticationPrincipal Jwt jwt, @PathVariable Long spaceId,
                                   @RequestParam(required = false) Long parentId) {
        return tree.children(userId(jwt), spaceId, parentId);
    }

    @GetMapping("/api/wiki/spaces/{spaceId}/pages/lookup")
    public List<PageNode> lookup(@AuthenticationPrincipal Jwt jwt, @PathVariable Long spaceId,
                                 @RequestParam List<String> title) {
        return tree.lookupByTitles(userId(jwt), spaceId, title);
    }

    @GetMapping("/api/wiki/spaces/{spaceId}/pages/recent")
    public List<PageNode> recent(@AuthenticationPrincipal Jwt jwt, @PathVariable Long spaceId,
                                 @RequestParam(defaultValue = "8") int limit) {
        return tree.recentlyUpdated(userId(jwt), spaceId, limit);
    }

    @GetMapping("/api/wiki/spaces/{spaceId}/pages/by-ids")
    public List<PageNode> byIds(@AuthenticationPrincipal Jwt jwt, @PathVariable Long spaceId,
                                @RequestParam List<Long> id) {
        return tree.byIds(userId(jwt), spaceId, id);
    }

    @GetMapping("/api/wiki/spaces/{spaceId}/pages/search")
    public List<PageNode> search(@AuthenticationPrincipal Jwt jwt, @PathVariable Long spaceId,
                                 @RequestParam String q) {
        return tree.searchByTitle(userId(jwt), spaceId, q);
    }

    @GetMapping("/api/wiki/pages/{pageId}/ancestors")
    public List<PageNode> ancestors(@AuthenticationPrincipal Jwt jwt, @PathVariable Long pageId) {
        return tree.ancestors(userId(jwt), pageId);
    }

    @GetMapping("/api/wiki/pages/{pageId}/descendants")
    public List<PageNode> descendants(@AuthenticationPrincipal Jwt jwt, @PathVariable Long pageId) {
        return tree.descendants(userId(jwt), pageId);
    }
}
