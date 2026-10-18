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
    @org.springframework.beans.factory.annotation.Value("${platform.wiki.blog.scan-limit:" + SCAN_LIMIT + "}")
    private int scanLimit = SCAN_LIMIT;

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
        return listPage(userId, spaceId, before, limit).items();
    }

    /** 쪽 결과 — nextBefore는 다음 요청의 before(더 없으면 null). 컨트롤러가 X-Next-Before 헤더로 낸다. */
    public record BlogPage(List<BlogPostView> items, Long nextBefore) {
    }

    public BlogPage listPage(long userId, long spaceId, Long before, Integer limit) {
        spaces.getForView(userId, spaceId);
        if (before == null && limit == null) {
            return new BlogPage(visible(userId, pages.findBlogRows(spaceId, Limit.unlimited())), null);
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
        boolean more = true;          // 커서 뒤에 글이 더 있을 수 있다
        Long next = null;
        for (int scanned = 0; scanned < scanLimit && out.size() < size && more; scanned += batch) {
            List<BlogRow> rows = cursorAt == null
                    ? pages.findBlogRows(spaceId, Limit.of(batch))
                    : pages.findBlogRowsBefore(spaceId, cursorAt, cursorId, Limit.of(batch));
            more = rows.size() == batch;
            Set<Long> allowed = rows.isEmpty() ? Set.of()
                    : effective.viewableIds(userId, rows.stream().map(BlogRow::id).toList());
            for (int k = 0; k < rows.size(); k++) {
                BlogRow row = rows.get(k);
                cursorAt = row.createdAt();
                cursorId = row.id();
                if (!allowed.contains(row.id())) continue;
                out.add(BlogPostView.from(row));
                if (out.size() == size) {
                    if (k < rows.size() - 1) more = true;
                    break;
                }
            }
        }
        // 다음 커서는 마지막으로 **스캔한** 글 — 쪽을 채웠으면 그게 마지막으로 준 글이고, 스캔 상한에서 멈췄으면
        // 숨은 글 구간의 끝이다. 보이는 글 기준으로 돌려주면 숨은 구간을 매번 다시 훑고 빈 목록이 끝처럼 보인다.
        if (more) next = cursorId;
        return new BlogPage(out, next);
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
