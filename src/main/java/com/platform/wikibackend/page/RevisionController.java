package com.platform.wikibackend.page;

import com.platform.wikibackend.page.dto.PageResponse;
import com.platform.wikibackend.page.dto.RevisionMeta;
import com.platform.wikibackend.page.dto.RevisionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.platform.wikibackend.space.SpaceController.userId;

@RestController
@RequestMapping("/api/wiki/pages/{pageId}/revisions")
@RequiredArgsConstructor
public class RevisionController {

    private final PageService pages;

    @GetMapping
    public List<RevisionMeta> list(@AuthenticationPrincipal Jwt jwt, @PathVariable Long pageId) {
        return pages.listRevisions(userId(jwt), pageId);
    }

    @GetMapping("/{version}")
    public RevisionResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long pageId, @PathVariable Integer version) {
        return pages.getRevision(userId(jwt), pageId, version);
    }

    @PostMapping("/{version}/restore")
    public PageResponse restore(@AuthenticationPrincipal Jwt jwt, @PathVariable Long pageId, @PathVariable Integer version) {
        return pages.restore(userId(jwt), pageId, version);
    }
}
