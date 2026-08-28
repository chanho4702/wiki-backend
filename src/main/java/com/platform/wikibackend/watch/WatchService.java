package com.platform.wikibackend.watch;

import com.platform.wikibackend.common.NotFoundException;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageWatch;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageWatchRepository;
import com.platform.wikibackend.space.SpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 페이지 구독(W21-4).
 *
 * 지금까지 알림 대상은 "작성자 + 편집자"로 코드에 박혀 있어 끌 수도, 관심 문서를 골라 켤 수도
 * 없었다. 이제 이 표가 단일 원장이다.
 *
 * 자동 구독은 컨플루언스와 같다 — 만들거나, 고치거나, 댓글을 단 문서는 자동으로 구독된다.
 * 대신 언제든 해제할 수 있다. (해제한 문서를 다시 편집하면 다시 구독된다 — 편집은 관심의 표현이라는
 * 컨플루언스의 기본 동작을 따른다.)
 */
@Service
@RequiredArgsConstructor
@Transactional
public class WatchService {

    private final PageWatchRepository watches;
    private final PageRepository pages;
    private final SpaceService spaces;
    private final EffectivePermissionService effective;

    @Transactional(readOnly = true)
    public boolean isWatching(long userId, long pageId) {
        Page page = requireVisible(userId, pageId);
        return watches.existsByPageIdAndUserId(page.getId(), userId);
    }

    public boolean watch(long userId, long pageId) {
        requireVisible(userId, pageId);
        if (!watches.existsByPageIdAndUserId(pageId, userId)) {
            watches.save(PageWatch.of(pageId, userId));
        }
        return true;
    }

    public boolean unwatch(long userId, long pageId) {
        requireVisible(userId, pageId);
        watches.deleteByPageIdAndUserId(pageId, userId);
        return false;
    }

    /** 편집·댓글 등 "관심의 표현"에서 부르는 조용한 구독. 이미 구독 중이면 아무것도 하지 않는다. */
    public void autoWatch(long pageId, long userId) {
        if (!watches.existsByPageIdAndUserId(pageId, userId)) {
            watches.save(PageWatch.of(pageId, userId));
        }
    }

    @Transactional(readOnly = true)
    public List<Long> watcherIds(long pageId) {
        return watches.findWatcherIds(pageId);
    }

    /** 구독은 볼 수 있는 문서에만 걸 수 있다 — 못 보는 문서의 알림을 받게 두면 제한이 새어나간다. */
    private Page requireVisible(long userId, long pageId) {
        Page page = pages.findById(pageId)
                .orElseThrow(() -> new NotFoundException("페이지 없음: " + pageId));
        spaces.require(userId, page.getSpaceId(), WikiAction.VIEW);
        effective.requireView(userId, page);
        return page;
    }
}
