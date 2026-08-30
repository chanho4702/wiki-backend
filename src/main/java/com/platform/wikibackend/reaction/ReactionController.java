package com.platform.wikibackend.reaction;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.platform.wikibackend.space.SpaceController.userId;

/**
 * 리액션 REST(W23). PUT은 켜기, DELETE는 끄기 — 둘 다 멱등이라 재시도해도 상태가 흔들리지 않는다.
 * 응답은 바뀐 뒤의 집계다. 화면이 낙관적으로 그린 것을 이 값으로 덮는다.
 */
@RestController
@RequiredArgsConstructor
public class ReactionController {

    private final ReactionService reactions;

    @GetMapping("/api/wiki/pages/{pageId}/reactions")
    public List<ReactionService.ReactionSummary> page(@AuthenticationPrincipal Jwt jwt,
                                                      @PathVariable long pageId) {
        return reactions.forPage(userId(jwt), pageId);
    }

    @PutMapping("/api/wiki/pages/{pageId}/reactions/{emoji}")
    public List<ReactionService.ReactionSummary> reactPage(@AuthenticationPrincipal Jwt jwt,
                                                           @PathVariable long pageId,
                                                           @PathVariable String emoji) {
        return reactions.setOnPage(userId(jwt), pageId, emoji, true);
    }

    @DeleteMapping("/api/wiki/pages/{pageId}/reactions/{emoji}")
    public List<ReactionService.ReactionSummary> unreactPage(@AuthenticationPrincipal Jwt jwt,
                                                             @PathVariable long pageId,
                                                             @PathVariable String emoji) {
        return reactions.setOnPage(userId(jwt), pageId, emoji, false);
    }

    @PutMapping("/api/wiki/comments/{commentId}/reactions/{emoji}")
    public List<ReactionService.ReactionSummary> reactComment(@AuthenticationPrincipal Jwt jwt,
                                                              @PathVariable long commentId,
                                                              @PathVariable String emoji) {
        return reactions.setOnComment(userId(jwt), commentId, emoji, true);
    }

    @DeleteMapping("/api/wiki/comments/{commentId}/reactions/{emoji}")
    public List<ReactionService.ReactionSummary> unreactComment(@AuthenticationPrincipal Jwt jwt,
                                                                @PathVariable long commentId,
                                                                @PathVariable String emoji) {
        return reactions.setOnComment(userId(jwt), commentId, emoji, false);
    }
}
