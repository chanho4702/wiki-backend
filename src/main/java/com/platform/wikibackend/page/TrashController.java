package com.platform.wikibackend.page;

import com.platform.wikibackend.page.dto.PageRestoreResponse;
import com.platform.wikibackend.page.dto.TrashItem;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static com.platform.wikibackend.space.SpaceController.userId;

/**
 * 휴지통(W21-1). 복원은 `/pages/{id}/restore` — 리비전 복원(`/pages/{id}/revisions/{v}/restore`)과
 * 경로가 다르다: 하나는 "지운 문서를 되살린다", 다른 하나는 "예전 내용으로 되돌린다".
 */
@RestController
@RequiredArgsConstructor
public class TrashController {

    private final TrashService trash;

    @GetMapping("/api/wiki/spaces/{spaceId}/trash")
    public List<TrashItem> list(@AuthenticationPrincipal Jwt jwt, @PathVariable Long spaceId) {
        return trash.list(userId(jwt), spaceId);
    }

    @DeleteMapping("/api/wiki/spaces/{spaceId}/trash")
    public Map<String, Integer> empty(@AuthenticationPrincipal Jwt jwt, @PathVariable Long spaceId) {
        return Map.of("purged", trash.empty(userId(jwt), spaceId));
    }

    @PostMapping("/api/wiki/pages/{id}/restore")
    public PageRestoreResponse restore(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return trash.restore(userId(jwt), id);
    }

    @DeleteMapping("/api/wiki/pages/{id}/purge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purge(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        trash.purge(userId(jwt), id);
    }
}
