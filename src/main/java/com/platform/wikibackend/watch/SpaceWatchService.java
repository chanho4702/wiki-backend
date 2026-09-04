package com.platform.wikibackend.watch;

import com.platform.wikibackend.domain.SpaceWatch;
import com.platform.wikibackend.repository.SpaceWatchRepository;
import com.platform.wikibackend.space.SpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 스페이스 구독(W27-4).
 *
 * 페이지 구독(V15)은 이미 있는 문서에만 걸 수 있어서, 아직 만들어지지 않은 문서 — 곧 새 문서 —
 * 를 지켜볼 방법이 없었다. 이 원장이 그 자리를 메운다.
 *
 * 자동 구독은 없다. 페이지 구독의 자동 구독은 "만들었다·고쳤다·댓글을 달았다"라는 관심의 사건이
 * 근거지만, 스페이스에는 그런 사건이 없다 — 스페이스를 만든 사람이 그 안의 모든 문서를 지켜보고
 * 싶다는 규칙은 어디에도 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SpaceWatchService {

    private final SpaceWatchRepository watches;
    private final SpaceService spaces;

    @Transactional(readOnly = true)
    public boolean isWatching(long userId, long spaceId) {
        requireVisible(userId, spaceId);
        return watches.existsBySpaceIdAndUserId(spaceId, userId);
    }

    public boolean watch(long userId, long spaceId) {
        requireVisible(userId, spaceId);
        if (!watches.existsBySpaceIdAndUserId(spaceId, userId)) {
            watches.save(SpaceWatch.of(spaceId, userId));
        }
        return true;
    }

    public boolean unwatch(long userId, long spaceId) {
        requireVisible(userId, spaceId);
        watches.deleteBySpaceIdAndUserId(spaceId, userId);
        return false;
    }

    @Transactional(readOnly = true)
    public List<Long> watcherIds(long spaceId) {
        return watches.findWatcherIds(spaceId);
    }

    /** 페이지 구독과 같은 규칙 — 볼 수 없는 스페이스는 구독할 수 없다(존재 자체가 새어나간다). */
    private void requireVisible(long userId, long spaceId) {
        spaces.getForView(userId, spaceId); // 없으면 404, VIEW 없으면 403
    }
}
