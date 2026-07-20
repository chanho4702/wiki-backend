package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRevision;
import com.platform.wikibackend.domain.Space;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class PageRepositoryTest {

    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;

    @Test
    void 스페이스별_페이지와_트리_자식을_조회한다() {
        Space s = spaces.save(Space.of("dev", "개발 위키", null, 1L));
        Page root = pages.save(Page.of(s.getId(), null, "루트", "내용", 1L));
        Page child = pages.save(Page.of(s.getId(), root.getId(), "자식", "내용", 1L));

        assertThat(pages.findBySpaceIdOrderById(s.getId())).hasSize(2);
        assertThat(pages.findByParentId(root.getId())).containsExactly(child);
    }

    @Test
    void 리비전_스냅샷은_버전_역순으로_조회된다() {
        Space s = spaces.save(Space.of("ops", "운영", null, 1L));
        Page p = pages.save(Page.of(s.getId(), null, "t", "v1", 1L));
        revisions.save(PageRevision.snapshotOf(p));
        p.edit("t", "v2", 2L);
        pages.save(p);
        revisions.save(PageRevision.snapshotOf(p));

        var list = revisions.findByPageIdOrderByVersionDesc(p.getId());
        assertThat(list).hasSize(2);
        assertThat(list.get(0).getVersion()).isEqualTo(2);
        assertThat(revisions.findByPageIdAndVersion(p.getId(), 1)).isPresent()
                .get().satisfies(r -> assertThat(r.getContent()).isEqualTo("v1"));
    }
}
