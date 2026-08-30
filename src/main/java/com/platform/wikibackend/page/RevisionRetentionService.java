package com.platform.wikibackend.page;

import com.platform.wikibackend.domain.PageRevision;
import com.platform.wikibackend.repository.PageRevisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 리비전 보관 정책(W25, 2026-08-30 결정): **문서당 최근 100개는 항상, 그보다 오래된 것은 90일 지나면 정리.**
 *
 * 지금까지는 무기한이었다. gzip(V16)으로 용량은 버텼지만 "언제까지"가 없으면 언젠가는 정책 없이
 * 지워야 하는 날이 온다. 둘을 겹친 이유: 개수만 보면 하루에 200번 고친 문서의 어제 버전이 사라지고,
 * 기간만 보면 1년에 세 번 고친 문서의 이력이 텅 빈다. 컨플루언스 기본과 같은 모양이다.
 *
 * 현재 버전(가장 큰 version)은 어떤 경우에도 지우지 않는다 — 리비전은 본문의 사본이자 되돌리기의 원천이다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RevisionRetentionService {

    private final PageRevisionRepository revisions;

    @Value("${platform.wiki.revisions.keep-count:100}")
    private int keepCount;

    @Value("${platform.wiki.revisions.retention:P90D}")
    private Duration retention;

    /** 지운 리비전 수. */
    @Transactional
    public int prune(Instant now) {
        Instant cutoff = now.minus(retention);
        int deleted = 0;
        for (Long pageId : revisions.findPageIdsWithMoreRevisionsThan(keepCount)) {
            List<PageRevision> ordered = revisions.findByPageIdOrderByVersionDesc(pageId); // 최신 → 오래된
            List<PageRevision> beyond = ordered.subList(Math.min(keepCount, ordered.size()), ordered.size());
            List<PageRevision> doomed = beyond.stream()
                    .filter(r -> r.getCreatedAt() != null && r.getCreatedAt().isBefore(cutoff))
                    .toList();
            if (doomed.isEmpty()) continue;
            revisions.deleteAllInBatch(doomed);
            deleted += doomed.size();
        }
        if (deleted > 0) log.info("리비전 보관 정리: {}건 삭제 (keep={}, retention={})", deleted, keepCount, retention);
        return deleted;
    }
}
