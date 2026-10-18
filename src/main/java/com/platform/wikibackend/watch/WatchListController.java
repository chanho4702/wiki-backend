package com.platform.wikibackend.watch;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.platform.wikibackend.space.SpaceController.userId;

/** 내 구독 목록(F-06). 단건 구독·해제는 문서/스페이스 경로(WatchController·SpaceWatchController) 그대로다. */
@Tag(name = "Watches", description = "내가 구독한 문서·스페이스 목록과 일괄 해제.")
@RestController
@RequiredArgsConstructor
public class WatchListController {

    private final WatchListService watches;

    @Operation(summary = "내가 구독한 문서와 스페이스를 조회한다 — 볼 수 없게 된 것은 제목을 가린다")
    @GetMapping("/api/wiki/watches")
    public WatchListService.WatchesResponse list(@AuthenticationPrincipal Jwt jwt) {
        return watches.list(userId(jwt));
    }

    @Operation(summary = "여러 문서·스페이스의 구독을 한 번에 해제한다")
    @PostMapping("/api/wiki/watches/unwatch")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unwatch(@AuthenticationPrincipal Jwt jwt, @RequestBody UnwatchRequest req) {
        watches.unwatch(userId(jwt), req.pageIds(), req.spaceIds());
    }

    public record UnwatchRequest(List<Long> pageIds, List<Long> spaceIds) {
    }
}
