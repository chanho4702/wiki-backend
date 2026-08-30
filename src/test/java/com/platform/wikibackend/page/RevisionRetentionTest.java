package com.platform.wikibackend.page;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRevision;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 리비전 보관 정책(W25) — 최근 keep개는 항상, 그 너머는 retention이 지나야 지운다. 현재 버전은 절대. */
@SpringBootTest
@ActiveProfiles("test")
class RevisionRetentionTest {

    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired RevisionRetentionService retention;
    @Autowired JdbcTemplate jdbc;

    Page page;

    @BeforeEach
    void setup() {
        revisions.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        Space space = spaces.save(Space.of("dev", "개발", null, 1L));
        page = pages.save(Page.of(space.getId(), null, "문서", "v1", 1L));
    }

    /** version 1..n 리비전을 만들고 전부 createdAt을 daysAgo 이전으로 민다. */
    private void revisionsAgedDays(int n, int daysAgo) {
        for (int v = 1; v <= n; v++) {
            page.edit("문서", "v" + v, 1L);
            revisions.save(PageRevision.snapshotOf(page));
        }
        Timestamp old = Timestamp.from(Instant.now().minus(Duration.ofDays(daysAgo)));
        jdbc.update("update page_revision set created_at = ? where page_id = ?", old, page.getId());
    }

    @Test
    void keep개를_넘는_오래된_리비전만_지우고_최근_keep개는_남긴다() {
        revisionsAgedDays(8, 120); // keep=5(test yml), retention=90d

        assertThat(retention.prune(Instant.now())).isEqualTo(3);
        assertThat(revisions.findByPageIdOrderByVersionDesc(page.getId()))
                .extracting(PageRevision::getVersion).containsExactly(9, 8, 7, 6, 5); // Page.of가 v1, 편집 8번이 v2..v9
    }

    @Test
    void keep개를_넘어도_retention이_안_지났으면_지우지_않는다() {
        revisionsAgedDays(8, 10);

        assertThat(retention.prune(Instant.now())).isZero();
        assertThat(revisions.findByPageIdOrderByVersionDesc(page.getId())).hasSize(8);
    }

    @Test
    void keep개_이하면_아무리_오래돼도_지우지_않는다() {
        revisionsAgedDays(5, 400);

        assertThat(retention.prune(Instant.now())).isZero();
        assertThat(revisions.findByPageIdOrderByVersionDesc(page.getId())).hasSize(5);
    }
}
