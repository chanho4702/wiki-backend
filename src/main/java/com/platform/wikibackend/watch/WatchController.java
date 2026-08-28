package com.platform.wikibackend.watch;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.platform.wikibackend.space.SpaceController.userId;

/** 페이지 구독(W21-4). 응답은 항상 현재 상태 하나 — 화면이 토글을 그대로 반영한다. */
@RestController
@RequestMapping("/api/wiki/pages/{pageId}/watch")
@RequiredArgsConstructor
public class WatchController {

    private final WatchService watches;

    @GetMapping
    public Map<String, Boolean> status(@AuthenticationPrincipal Jwt jwt, @PathVariable Long pageId) {
        return Map.of("watching", watches.isWatching(userId(jwt), pageId));
    }

    @PostMapping
    public Map<String, Boolean> watch(@AuthenticationPrincipal Jwt jwt, @PathVariable Long pageId) {
        return Map.of("watching", watches.watch(userId(jwt), pageId));
    }

    @DeleteMapping
    public Map<String, Boolean> unwatch(@AuthenticationPrincipal Jwt jwt, @PathVariable Long pageId) {
        return Map.of("watching", watches.unwatch(userId(jwt), pageId));
    }
}
