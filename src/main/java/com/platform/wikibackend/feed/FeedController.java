package com.platform.wikibackend.feed;

import com.platform.wikibackend.personal.PersonalService.StarredPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * 스페이스를 가로지르는 문서 피드 — 사람 프로필의 최근 작업(F-07), 홈의 인기·팔로잉.
 * 홈의 "최근 업데이트"는 스페이스별 경로(`/spaces/{id}/pages/recent`)를 화면이 합치는데, 스페이스가 많은
 * 설치에서 요청 폭주(W-13)를 냈다 — 새 피드는 처음부터 한 요청으로 준다.
 */
@Tag(name = "Feed", description = "사람별 최근 문서, 인기·팔로잉 피드.")
@RestController
@RequiredArgsConstructor
public class FeedController {

    private final FeedService feed;

    @Operation(summary = "그 사람이 만들었거나 마지막으로 고친 문서를 최근 수정순으로 조회한다(호출자가 볼 수 있는 것만)")
    @GetMapping("/api/wiki/users/{userId}/pages")
    public List<StarredPage> personPages(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "사용자 ID") @PathVariable("userId") long personId,
            @Parameter(description = "최대 건수(기본 20, 최대 50)") @RequestParam(defaultValue = "20") int limit) {
        return feed.personPages(userId(jwt), personId, limit);
    }

    @Operation(summary = "최근 90일 안에 수정된 게시 문서를 누적 조회수순으로 조회한다")
    @GetMapping("/api/wiki/feed/popular")
    public List<FeedService.FeedPage> popular(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "최대 건수(기본 20, 최대 50)") @RequestParam(defaultValue = "20") int limit) {
        return feed.popular(userId(jwt), limit);
    }

    @Operation(summary = "구독한 스페이스·문서의 게시 문서를 최근 수정순으로 조회한다")
    @GetMapping("/api/wiki/feed/following")
    public List<FeedService.FeedPage> following(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "최대 건수(기본 20, 최대 50)") @RequestParam(defaultValue = "20") int limit) {
        return feed.following(userId(jwt), limit);
    }
}
