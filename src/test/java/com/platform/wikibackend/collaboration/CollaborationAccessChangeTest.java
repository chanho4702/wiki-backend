package com.platform.wikibackend.collaboration;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.CollaborationDraftMetadata;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRevision;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.page.ChildrenPolicy;
import com.platform.wikibackend.page.PageMoveService;
import com.platform.wikibackend.page.PageService;
import com.platform.wikibackend.page.dto.PageMoveRequest;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.PageRestrictionService;
import com.platform.wikibackend.permission.dto.RestrictionPrincipal;
import com.platform.wikibackend.repository.CollaborationDraftMetadataRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 43번 높음-1: 접근 범위가 바뀌는 쓰기(휴지통·이동·페이지 제한) 뒤에, 공동 초안이 있는 room에만 커밋 후
 * 권한 재확인 신호가 나간다. 롤백된 변경은 아무 세션도 끊지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class CollaborationAccessChangeTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired CollaborationDraftMetadataRepository drafts;
    @Autowired FakePermissionClient permissions;
    @Autowired RecordingCollaborationRevokeSignal signal;
    @Autowired PageService pageService;
    @Autowired PageMoveService moveService;
    @Autowired PageRestrictionService restrictionService;
    @Autowired PlatformTransactionManager txManager;

    Space space;
    Page parent;
    Page child;
    Page plain;

    @BeforeEach
    void setup() {
        CollaborationDraftResetTest.addServiceOwnedColumns(jdbc);
        drafts.deleteAll();
        revisions.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        permissions.reset();
        signal.reset();

        space = spaces.save(Space.of("revoke", "권한 재확인", null, 1L));
        permissions.allowAll(1L);
        parent = pages.save(Page.of(space.getId(), null, "부모", "본문", 1L));
        child = pages.save(Page.of(space.getId(), parent.getId(), "자식", "본문", 1L));
        plain = pages.save(Page.of(space.getId(), null, "초안 없음", "본문", 1L));
        for (Page p : List.of(parent, child, plain)) revisions.save(PageRevision.snapshotOf(p));
        // 공동 편집 세션이 열린 적 있는 문서(초안 행)는 부모·자식뿐이다.
        drafts.save(CollaborationDraftMetadata.of(parent.getId(), 1L, 1L));
        drafts.save(CollaborationDraftMetadata.of(child.getId(), 1L, 1L));
    }

    private static String room(Page page) {
        return "page:" + page.getId();
    }

    @Test
    void 하위까지_휴지통으로_보내면_초안이_있는_room_전부에_재확인_신호() {
        pageService.delete(1L, parent.getId(), ChildrenPolicy.CASCADE);

        assertThat(signal.rooms()).containsExactlyInAnyOrder(room(parent), room(child));
    }

    @Test
    void 초안이_없는_문서는_신호를_보내지_않는다() {
        pageService.delete(1L, plain.getId(), null);

        assertThat(signal.sent).isEmpty();
    }

    @Test
    void 부모가_바뀌는_이동은_서브트리에_재확인_신호() {
        moveService.move(1L, parent.getId(), new PageMoveRequest(null, plain.getId(), null, null, true));

        assertThat(signal.rooms()).containsExactlyInAnyOrder(room(parent), room(child));
    }

    @Test
    void 같은_부모_안의_재정렬은_신호가_없다() {
        moveService.move(1L, parent.getId(), new PageMoveRequest(null, null, plain.getId(), null, true));

        assertThat(signal.sent).isEmpty();
    }

    @Test
    void 페이지_제한을_바꾸면_상속받는_자손까지_재확인_신호() {
        restrictionService.replace(1L, parent.getId(),
                List.of(new RestrictionPrincipal("USER", 1L)), List.of());

        assertThat(signal.rooms()).containsExactlyInAnyOrder(room(parent), room(child));
    }

    @Test
    void 롤백된_변경은_신호를_보내지_않는다() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            pageService.delete(1L, parent.getId(), ChildrenPolicy.CASCADE);
            throw new IllegalStateException("롤백");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(signal.sent).isEmpty();
    }
}
