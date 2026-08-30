package com.platform.wikibackend.page;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.page.dto.BlogPostView;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.space.SpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 블로그(W24) — 트리 밖에서 날짜순으로 읽는 글 목록.
 *
 * 공지·회고·주간 소식처럼 "어디에 넣을지"보다 "언제 썼는지"가 중요한 글이 있다. 트리에 넣으면
 * 폴더 이름을 고민하게 되고, 결국 "공지" 폴더에 시간순으로 쌓인다 — 그럼 그건 블로그다.
 *
 * 글은 페이지다(type=BLOG). 본문·리비전·댓글·라벨·검색·권한이 전부 페이지와 같고, 다른 것은
 * 트리에 없다는 것뿐이다. 그래서 목록을 읽을 때도 페이지와 같은 권한 필터를 탄다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlogService {

    private final PageRepository pages;
    private final SpaceService spaces;
    private final EffectivePermissionService effective;

    public List<BlogPostView> list(long userId, long spaceId) {
        spaces.getForView(userId, spaceId);
        List<Page> posts = pages.findBlogPosts(spaceId);
        Set<Long> visible = effective.viewablePageIds(userId, posts);
        return posts.stream()
                .filter(p -> visible.contains(p.getId()))
                .map(BlogPostView::from)
                .toList();
    }
}
