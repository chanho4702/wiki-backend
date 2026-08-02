package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRevision;
import com.platform.wikibackend.domain.Space;
import org.junit.jupiter.api.BeforeEach;
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
    @Autowired AttachmentRepository attachments;

    /**
     * @SpringBootTest 클래스들은 커밋하고 자기 @BeforeEach에서만 청소해서 마지막 테스트 데이터가 남는다.
     * H2 인메모리 DB는 컨텍스트 사이에 공유되므로(DB_CLOSE_DELAY=-1) 그 잔여물이 이쪽으로 넘어온다 —
     * space.key unique 충돌이 실행 순서에 따라 터졌다. 여기서도 먼저 비워 순서 의존을 끊는다.
     *
     * deleteAllInBatch()인 이유: Hibernate는 플러시 때 INSERT를 DELETE보다 먼저 실행한다.
     * 지연 삭제로는 뒤이은 save()가 아직 살아 있는 행과 충돌한다.
     */
    @BeforeEach
    void clean() {
        attachments.deleteAllInBatch();
        revisions.deleteAllInBatch();
        pages.deleteAllInBatch();
        spaces.deleteAllInBatch();
    }

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
