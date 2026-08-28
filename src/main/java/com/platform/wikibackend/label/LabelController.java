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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.platform.wikibackend.space.SpaceController.userId;

/** 라벨·백링크(W21-2). */
@RestController
@RequiredArgsConstructor
public class LabelController {

    private final LabelService labels;

    @GetMapping("/api/wiki/pages/{pageId}/labels")
    public List<String> list(@AuthenticationPrincipal Jwt jwt, @PathVariable Long pageId) {
        return labels.list(userId(jwt), pageId);
    }

    @PutMapping("/api/wiki/pages/{pageId}/labels")
    public List<String> replace(@AuthenticationPrincipal Jwt jwt, @PathVariable Long pageId,
                                @Valid @RequestBody LabelsRequest req) {
        return labels.replace(userId(jwt), pageId, req.labels());
    }

    @GetMapping("/api/wiki/pages/{pageId}/backlinks")
    public List<PageTreeItem> backlinks(@AuthenticationPrincipal Jwt jwt, @PathVariable Long pageId) {
        return labels.backlinks(userId(jwt), pageId);
    }

    @GetMapping("/api/wiki/spaces/{spaceId}/labels")
    public List<LabelService.LabelCountResponse> inSpace(@AuthenticationPrincipal Jwt jwt,
                                                        @PathVariable Long spaceId) {
        return labels.listInSpace(userId(jwt), spaceId);
    }

    @GetMapping("/api/wiki/spaces/{spaceId}/labels/{name}/pages")
    public List<PageTreeItem> pagesWithLabel(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable Long spaceId, @PathVariable String name) {
        return labels.pagesWithLabel(userId(jwt), spaceId, name);
    }

    public record LabelsRequest(@NotNull List<String> labels) {
    }
}
