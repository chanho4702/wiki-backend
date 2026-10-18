package com.platform.wikibackend.page;

import com.platform.wikibackend.page.dto.BlogPostView;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;

import static com.platform.wikibackend.space.SpaceController.userId;

/** 블로그(W24). 글의 생성·편집·삭제는 페이지 API 그대로다(type=blog) — 목록만 따로 있다. */
@Tag(name = "Blog", description = "스페이스 블로그 글 목록. 글 자체는 페이지 API로 다룬다.")
@RestController
@RequiredArgsConstructor
public class BlogController {

    private final BlogService blog;

    /** 블로그 쪽 목록의 다음 커서 헤더 — 이 값을 다음 요청의 before로. 더 없으면 헤더가 없다. */
    public static final String NEXT_BEFORE = "X-Next-Before";

    @Operation(summary = "스페이스의 블로그 글을 조회한다")
    @GetMapping("/api/wiki/spaces/{spaceId}/blog")
    public org.springframework.http.ResponseEntity<List<BlogPostView>> list(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "스페이스 ID") @PathVariable long spaceId,
                                   @Parameter(description = "이전 쪽의 마지막 글 ID — 그보다 오래된 글부터. 비우면 최신부터")
                                   @RequestParam(required = false) Long before,
                                   @Parameter(description = "한 쪽 글 수(최대 100). before와 함께 둘 다 비우면 전부")
                                   @RequestParam(required = false) Integer limit) {
        // 본문은 배열 그대로(하위 호환). 다음 쪽 커서는 헤더로 — 더 없으면 헤더가 없다.
        BlogService.BlogPage page = blog.listPage(userId(jwt), spaceId, before, limit);
        var response = org.springframework.http.ResponseEntity.ok();
        if (page.nextBefore() != null) response.header(NEXT_BEFORE, String.valueOf(page.nextBefore()));
        return response.body(page.items());
    }
}
