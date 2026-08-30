package com.platform.wikibackend.page;

import com.platform.wikibackend.page.dto.PageResponse;
import com.platform.wikibackend.page.dto.TrashItem;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.platform.wikibackend.space.SpaceController.userId;

/** 페이지 보관 REST(W23). 목록 행은 휴지통과 같은 모양(TrashItem) — 화면도 같은 표를 쓴다. */
@RestController
@RequiredArgsConstructor
public class ArchiveController {

    private final ArchiveService archive;

    @GetMapping("/api/wiki/spaces/{spaceId}/archive")
    public List<TrashItem> list(@AuthenticationPrincipal Jwt jwt, @PathVariable Long spaceId) {
        return archive.list(userId(jwt), spaceId);
    }

    @PostMapping("/api/wiki/pages/{id}/archive")
    public PageResponse archive(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return archive.archive(userId(jwt), id);
    }

    @PostMapping("/api/wiki/pages/{id}/unarchive")
    public PageResponse unarchive(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return archive.unarchive(userId(jwt), id);
    }
}
