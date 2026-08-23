package com.platform.wikibackend.page;

import com.platform.wikibackend.page.dto.PageCreateRequest;
import com.platform.wikibackend.page.dto.CollaborationDraftCommitRequest;
import com.platform.wikibackend.page.dto.CollaborationDraftCommitResponse;
import com.platform.wikibackend.page.dto.PageResponse;
import com.platform.wikibackend.page.dto.PageTreeItem;
import com.platform.wikibackend.page.dto.PageUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.platform.wikibackend.space.SpaceController.userId;

@RestController
@RequiredArgsConstructor
public class PageController {

    private final PageService pages;

    @PostMapping("/api/wiki/pages")
    @ResponseStatus(HttpStatus.CREATED)
    public PageResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PageCreateRequest req) {
        return pages.create(userId(jwt), req);
    }

    @GetMapping("/api/wiki/pages/{id}")
    public PageResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return pages.get(userId(jwt), id);
    }

    @GetMapping("/api/wiki/spaces/{spaceId}/pages")
    public List<PageTreeItem> tree(@AuthenticationPrincipal Jwt jwt, @PathVariable Long spaceId) {
        return pages.tree(userId(jwt), spaceId);
    }

    @PutMapping("/api/wiki/pages/{id}")
    public PageResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                               @Valid @RequestBody PageUpdateRequest req) {
        return pages.update(userId(jwt), id, req);
    }

    @PutMapping("/api/wiki/pages/{id}/collaboration-draft")
    public CollaborationDraftCommitResponse commitCollaborationDraft(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @Valid @RequestBody CollaborationDraftCommitRequest req) {
        return pages.commitCollaborationDraft(userId(jwt), id, req);
    }

    @PostMapping("/api/wiki/pages/{id}/copy")
    @ResponseStatus(HttpStatus.CREATED)
    public PageResponse copy(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return pages.copy(userId(jwt), id);
    }

    @PostMapping("/api/wiki/pages/{id}/publish")
    public PageResponse publish(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return pages.publish(userId(jwt), id);
    }

    /**
     * children=promote|cascade — 자식이 있을 때만 의미가 있다. 미지정이면 자식이 있는 경우 409.
     * (Spring 기본 enum 변환은 대소문자를 구분해 소문자 값을 거부하므로 문자열로 받아 변환한다.)
     */
    @DeleteMapping("/api/wiki/pages/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                       @RequestParam(required = false) String children) {
        pages.delete(userId(jwt), id, ChildrenPolicy.from(children));
    }
}
