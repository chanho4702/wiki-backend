package com.platform.wikibackend.watch;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import static com.platform.wikibackend.space.SpaceController.userId;

/**
 * 스페이스 구독(W27-4). 응답 형태는 페이지 구독과 같다 — 화면이 같은 토글로 다룬다.
 *
 * 켜기는 POST와 PUT 둘 다 받는다. 구독은 멱등한 상태 설정이라 PUT이 자연스럽지만, 페이지 구독이
 * 이미 POST로 나가 있어 프론트가 한 코드로 두 자원을 다룰 수 있게 둘 다 열어 둔다.
 */
@Tag(name = "Watch", description = "페이지·스페이스 구독 — 변경이 생기면 알림을 받는다.")
@RestController
@RequestMapping("/api/wiki/spaces/{spaceId}/watch")
@RequiredArgsConstructor
public class SpaceWatchController {

    private final SpaceWatchService watches;

    @Operation(summary = "스페이스를 구독 중인지 조회한다")
    @GetMapping
    public Map<String, Boolean> status(@AuthenticationPrincipal Jwt jwt, @PathVariable Long spaceId) {
        return Map.of("watching", watches.isWatching(userId(jwt), spaceId));
    }

    @Operation(summary = "스페이스를 구독한다")
    @PutMapping
    public Map<String, Boolean> watch(@AuthenticationPrincipal Jwt jwt, @PathVariable Long spaceId) {
        return Map.of("watching", watches.watch(userId(jwt), spaceId));
    }

    @Operation(summary = "스페이스를 구독한다 — PUT과 같은 동작(프론트 호환용)")
    @PostMapping
    public Map<String, Boolean> watchViaPost(@AuthenticationPrincipal Jwt jwt, @PathVariable Long spaceId) {
        return watch(jwt, spaceId);
    }

    @Operation(summary = "스페이스 구독을 해제한다")
    @DeleteMapping
    public Map<String, Boolean> unwatch(@AuthenticationPrincipal Jwt jwt, @PathVariable Long spaceId) {
        return Map.of("watching", watches.unwatch(userId(jwt), spaceId));
    }
}
