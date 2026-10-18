package com.platform.wikibackend.page;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.page.dto.BlogPostView;
import com.platform.wikibackend.page.dto.BlogRow;
import org.springframework.data.domain.Limit;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.space.SpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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

    /** 한 쪽 상한 — 화면의 "더 보기" 한 번이 읽는 양. */
    public static final int PAGE_MAX = 100;
    static final int PAGE_DEFAULT = 30;
    /** 권한으로 걸러진 만큼 더 읽을 때의 총 스캔 상한 — 볼 수 없는 글이 아주 많아도 요청이 끝없이 돌지 않게. */
    static final int SCAN_LIMIT = 5_000;

    /** 전부(구 계약) — 쪽 파라미터가 없는 호출. 본문은 앞부분만 읽는다. */
    public List<BlogPostView> list(long userId, long spaceId) {
        return list(userId, spaceId, null, null);
    }

    /**
     * 최신순 목록. before·limit이 둘 다 없으면 예전처럼 전부 준다(구 화면 호환 — 잘린 목록을 조용히
     * 받으면 오래된 글이 사라진 것처럼 보인다). 있으면 (createdAt, id) 키셋 쪽: before는 이전 쪽 마지막 글 id.
     * 어느 쪽이든 본문 전체는 읽지 않는다(감사 W-16).
     */
    public List<BlogPostView> list(long userId, long spaceId, Long before, Integer limit) {
        spaces.getForView(userId, spaceId);
        if (before == null && limit == null) {
            return visible(userId, pages.findBlogRows(spaceId, Limit.unlimited()));
        }
        int size = limit == null ? PAGE_DEFAULT : Math.max(1, Math.min(limit, PAGE_MAX));
        java.time.Instant cursorAt = null;
        Long cursorId = null;
        if (before != null) {
            Page anchor = pages.findById(before)
                    .filter(p -> p.getSpaceId().equals(spaceId))
                    .orElseThrow(() -> new IllegalArgumentException("before 글을 찾을 수 없습니다: " + before));
            cursorAt = anchor.getCreatedAt();
            cursorId = anchor.getId();
        }
        List<BlogPostView> out = new ArrayList<>();
        int batch = size * 2;
        for (int scanned = 0; scanned < SCAN_LIMIT && out.size() < size; scanned += batch) {
            List<BlogRow> rows = cursorAt == null
                    ? pages.findBlogRows(spaceId, Limit.of(batch))
                    : pages.findBlogRowsBefore(spaceId, cursorAt, cursorId, Limit.of(batch));
            if (rows.isEmpty()) break;
            for (BlogPostView view : visible(userId, rows)) {
                out.add(view);
                if (out.size() == size) break;
            }
            BlogRow last = rows.get(rows.size() - 1);
            cursorAt = last.createdAt();
            cursorId = last.id();
            if (rows.size() < batch) break;
        }
        return out;
    }

    private List<BlogPostView> visible(long userId, List<BlogRow> rows) {
        if (rows.isEmpty()) return List.of();
        Set<Long> allowed = effective.viewableIds(userId, rows.stream().map(BlogRow::id).toList());
        return rows.stream()
                .filter(r -> allowed.contains(r.id()))
                .map(BlogPostView::from)
                .toList();
    }
}
