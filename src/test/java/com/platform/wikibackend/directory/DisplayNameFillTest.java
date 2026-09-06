package com.platform.wikibackend.directory;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageComment;
import com.platform.wikibackend.domain.PageRevision;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageCommentRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import com.platform.wikibackend.security.AccountStatusInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이름 폴백 보강 — 화면에 {@code 사용자 #7}이 남던 자리를 org 원장으로 채운다.
 *
 * <p>두 규칙이 함께 성립해야 한다. 빈 자리는 채우고, <b>남아 있는 스냅샷은 건드리지 않는다</b> —
 * 저장된 이름은 "그때 그 사람이 이 이름이었다"는 기록이라 지금 이름으로 덮으면 옛 문서의 서명이
 * 조용히 바뀐다.
 */
@SpringBootTest
@ActiveProfiles("test")
class DisplayNameFillTest {

    private static final long VIEWER = 8801L;
    private static final long AUTHOR = 8802L;

    @Autowired WebApplicationContext context;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired PageCommentRepository comments;
    @Autowired FakePermissionClient perms;
    @Autowired FakeMemberDirectory directory;
    @Autowired AccountStatusInterceptor gate;

    MockMvc mvc;
    Space space;
    Page page;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        comments.deleteAllInBatch();
        revisions.deleteAllInBatch();
        TestPages.deleteAll(jdbc);
        spaces.deleteAllInBatch();
        perms.reset();
        directory.reset();
        gate.evictAll();
        space = spaces.save(Space.of("nm" + (System.nanoTime() % 100000), "이름", null, AUTHOR));
        perms.allow(VIEWER, space.getId(), WikiAction.VIEW);
        page = pages.save(Page.of(space.getId(), null, "문서", "본문", AUTHOR));
    }

    @AfterEach
    void restore() {
        directory.reset();
        gate.evictAll();
    }

    /** V28 이전 리비전은 편집자 이름이 없다 — 그 자리를 원장이 채운다 */
    @Test
    void 이름이_없는_옛_리비전은_원장에서_채운다() throws Exception {
        revisions.save(PageRevision.imported(page.getId(), 1, "문서", "본문", AUTHOR, null, null));
        directory.put(AUTHOR, "김찬호", "author@org.example", "ACTIVE");

        mvc.perform(get("/api/wiki/pages/{id}/revisions", page.getId()).with(asUser(VIEWER, "Viewer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].editedByName").value("김찬호"));
    }

    /** 저장된 이름은 지금 이름으로 덮지 않는다 — 그때의 서명이다 */
    @Test
    void 저장된_이름은_원장_이름으로_덮지_않는다() throws Exception {
        revisions.save(PageRevision.imported(page.getId(), 1, "문서", "본문", AUTHOR, "옛이름", null));
        directory.put(AUTHOR, "새이름", "author@org.example", "ACTIVE");

        mvc.perform(get("/api/wiki/pages/{id}/revisions", page.getId()).with(asUser(VIEWER, "Viewer")))
                .andExpect(jsonPath("$[0].editedByName").value("옛이름"));
    }

    /** 원장이 그 사람을 모르면 채우지 않는다 — 화면이 쓰던 폴백이 그대로 남는다 */
    @Test
    void 원장이_모르면_비운_채로_둔다() throws Exception {
        revisions.save(PageRevision.imported(page.getId(), 1, "문서", "본문", AUTHOR, null, null));

        mvc.perform(get("/api/wiki/pages/{id}/revisions", page.getId()).with(asUser(VIEWER, "Viewer")))
                .andExpect(jsonPath("$[0].editedByName").doesNotExist()); // 기본 Jackson은 null을 싣지 않는다면 없음, 실으면 null
    }

    /** org 불능도 마찬가지다 — 이름 하나 때문에 이력 조회가 503이 되지 않는다 */
    @Test
    void 원장이_불능이어도_이력은_200이다() throws Exception {
        revisions.save(PageRevision.imported(page.getId(), 1, "문서", "본문", AUTHOR, null, null));
        directory.setFailed(true);

        mvc.perform(get("/api/wiki/pages/{id}/revisions", page.getId()).with(asUser(VIEWER, "Viewer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].editedByName").doesNotExist()); // 기본 Jackson은 null을 싣지 않는다면 없음, 실으면 null
    }

    @Test
    void 단건_리비전도_채운다() throws Exception {
        revisions.save(PageRevision.imported(page.getId(), 1, "문서", "본문", AUTHOR, null, null));
        directory.put(AUTHOR, "김찬호", "author@org.example", "ACTIVE");

        mvc.perform(get("/api/wiki/pages/{pageId}/revisions/{version}", page.getId(), 1)
                        .with(asUser(VIEWER, "Viewer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editedByName").value("김찬호"));
    }

    /** 이름이 이미 있으면 원장을 부르지 않는다 — 대부분의 리비전이 여기서 끝난다 */
    @Test
    void 채울_것이_없으면_원장을_부르지_않는다() throws Exception {
        revisions.save(PageRevision.imported(page.getId(), 1, "문서", "본문", AUTHOR, "김찬호", null));
        gate.evictAll();
        directory.reset();

        mvc.perform(get("/api/wiki/pages/{id}/revisions", page.getId()).with(asUser(VIEWER, "Viewer")))
                .andExpect(status().isOk());

        // 계정 상태 게이트가 요청자를 한 번 묻는 것 외에는 조회가 없다
        assertThat(directory.calls()).allMatch(ids -> ids.equals(java.util.List.of(VIEWER)));
    }

    /** 토큰에 name이 없던 요청으로 만들어진 옛 댓글은 `사용자 #N`으로 저장돼 있다 */
    @Test
    void 폴백으로_저장된_댓글_작성자를_채운다() throws Exception {
        comments.save(PageComment.of(page.getId(), null, AUTHOR, "사용자 #" + AUTHOR, "옛 댓글"));
        directory.put(AUTHOR, "김찬호", "author@org.example", "ACTIVE");

        mvc.perform(get("/api/wiki/pages/{id}/comments", page.getId()).with(asUser(VIEWER, "Viewer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].authorName").value("김찬호"));
    }

    @Test
    void 이름이_남아_있는_댓글은_그대로_둔다() throws Exception {
        comments.save(PageComment.of(page.getId(), null, AUTHOR, "옛이름", "옛 댓글"));
        directory.put(AUTHOR, "새이름", "author@org.example", "ACTIVE");

        mvc.perform(get("/api/wiki/pages/{id}/comments", page.getId()).with(asUser(VIEWER, "Viewer")))
                .andExpect(jsonPath("$[0].authorName").value("옛이름"));
    }

    @Test
    void 원장이_모르는_댓글_작성자는_폴백이_그대로_남는다() throws Exception {
        comments.save(PageComment.of(page.getId(), null, AUTHOR, "사용자 #" + AUTHOR, "옛 댓글"));

        mvc.perform(get("/api/wiki/pages/{id}/comments", page.getId()).with(asUser(VIEWER, "Viewer")))
                .andExpect(jsonPath("$[0].authorName").value("사용자 #" + AUTHOR));
    }
}
