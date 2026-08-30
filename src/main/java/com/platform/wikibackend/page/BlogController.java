package com.platform.wikibackend.page;

import com.platform.wikibackend.page.dto.BlogPostView;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.platform.wikibackend.space.SpaceController.userId;

/** 블로그(W24). 글의 생성·편집·삭제는 페이지 API 그대로다(type=blog) — 목록만 따로 있다. */
@RestController
@RequiredArgsConstructor
public class BlogController {

    private final BlogService blog;

    @GetMapping("/api/wiki/spaces/{spaceId}/blog")
    public List<BlogPostView> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long spaceId) {
        return blog.list(userId(jwt), spaceId);
    }
}
