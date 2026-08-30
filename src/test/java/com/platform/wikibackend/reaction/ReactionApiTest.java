package com.platform.wikibackend.reaction;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageComment;
import com.platform.wikibackend.domain.PageRestriction;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageCommentRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRestrictionRepository;
import com.platform.wikibackend.repository.ReactionRepository;
import com.platform.wikibackend.repository.SpaceRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 리액션(W23). "잘 봤다"를 표현할 방법이 댓글뿐이었다 — 한마디 남기자고 댓글을 쓰면
 * 스레드가 잡음으로 차고, 그래서 아무도 안 남긴다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReactionApiTest {

    private static final long ME = 1L;
    private static final long OTHER = 2L;

    @Autowired WebApplicationContext context;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageCommentRepository comments;
    @Autowired ReactionRepository reactions;
    @Autowired PageRestrictionRepository restrictions;
    @Autowired FakePermissionClient perms;

    MockMvc mvc;
    Space space;
    Page page;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        reactions.deleteAllInBatch();
        comments.deleteAllInBatch();
        restrictions.deleteAllInBatch();
        TestPages.deleteAll(jdbc);
        spaces.deleteAllInBatch();
        perms.reset();

        space = spaces.save(Space.of("rx" + (System.nanoTime() % 100000), "리액션", null, ME));
        perms.allow(ME, space.getId(), WikiAction.VIEW);
        perms.allow(OTHER, space.getId(), WikiAction.VIEW);
        page = pages.save(Page.of(space.getId(), null, "문서", "본문", ME));
    }

    @Test
    void 페이지에_리액션을_켜고_끈다() throws Exception {
        mvc.perform(put("/api/wiki/pages/{id}/reactions/{e}", page.getId(), "👍").with(asUser(ME, "Me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].emoji").value("👍"))
                .andExpect(jsonPath("$[0].count").value(1))
                .andExpect(jsonPath("$[0].reacted").value(true));

        mvc.perform(delete("/api/wiki/pages/{id}/reactions/{e}", page.getId(), "👍").with(asUser(ME, "Me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** 두 번 눌러도 한 번이다 — 재시도가 수를 부풀리면 집계를 믿을 수 없다. */
    @Test
    void 같은_리액션은_두_번_눌러도_하나다() throws Exception {
        mvc.perform(put("/api/wiki/pages/{id}/reactions/{e}", page.getId(), "👍").with(asUser(ME, "Me")));
        mvc.perform(put("/api/wiki/pages/{id}/reactions/{e}", page.getId(), "👍").with(asUser(ME, "Me")))
                .andExpect(jsonPath("$[0].count").value(1));
    }

    @Test
    void 집계는_사람별로_합쳐지고_내가_눌렀는지를_따로_준다() throws Exception {
        mvc.perform(put("/api/wiki/pages/{id}/reactions/{e}", page.getId(), "👍").with(asUser(ME, "Me")));
        mvc.perform(put("/api/wiki/pages/{id}/reactions/{e}", page.getId(), "👍").with(asUser(OTHER, "Other")));

        mvc.perform(get("/api/wiki/pages/{id}/reactions", page.getId()).with(asUser(OTHER, "Other")))
                .andExpect(jsonPath("$[0].count").value(2))
                .andExpect(jsonPath("$[0].reacted").value(true));
        mvc.perform(get("/api/wiki/pages/{id}/reactions", page.getId()).with(asUser(99L, "Stranger")))
                .andExpect(status().isForbidden());
    }

    /** 아무 문자나 받으면 집계 화면이 예측 불가능한 기호로 찬다. */
    @Test
    void 집합에_없는_이모지는_거부한다() throws Exception {
        mvc.perform(put("/api/wiki/pages/{id}/reactions/{e}", page.getId(), "💩").with(asUser(ME, "Me")))
                .andExpect(status().isBadRequest());
    }

    /** 댓글 목록이 리액션을 함께 준다 — 댓글마다 따로 묻지 않는다. */
    @Test
    void 댓글_리액션은_댓글_목록에_함께_온다() throws Exception {
        PageComment c = comments.save(PageComment.of(page.getId(), null, ME, "Me", "댓글"));

        mvc.perform(put("/api/wiki/comments/{id}/reactions/{e}", c.getId(), "🎉").with(asUser(OTHER, "Other")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].emoji").value("🎉"));

        mvc.perform(get("/api/wiki/pages/{id}/comments", page.getId()).with(asUser(ME, "Me")))
                .andExpect(jsonPath("$[0].reactions[0].emoji").value("🎉"))
                .andExpect(jsonPath("$[0].reactions[0].count").value(1))
                .andExpect(jsonPath("$[0].reactions[0].reacted").value(false));
    }

    /** 제한된 문서는 볼 수 없으면 누를 수도 없다 — 리액션이 존재 여부를 흘리면 안 된다. */
    @Test
    void 볼_수_없는_문서에는_누를_수_없다() throws Exception {
        restrictions.save(PageRestriction.of(page.getId(), PageRestriction.Type.VIEW,
                PageRestriction.PrincipalType.USER, ME, ME));

        mvc.perform(put("/api/wiki/pages/{id}/reactions/{e}", page.getId(), "👍").with(asUser(OTHER, "Other")))
                .andExpect(status().isForbidden());
    }

    /** 리액션은 FK 없이 매달려 있다 — 댓글을 지우면 함께 걷어내야 다음 id가 그 수를 물려받지 않는다. */
    @Test
    void 댓글을_지우면_리액션도_사라진다() throws Exception {
        PageComment c = comments.save(PageComment.of(page.getId(), null, ME, "Me", "댓글"));
        mvc.perform(put("/api/wiki/comments/{id}/reactions/{e}", c.getId(), "👍").with(asUser(ME, "Me")));

        mvc.perform(delete("/api/wiki/comments/{id}", c.getId()).with(asUser(ME, "Me")))
                .andExpect(status().isNoContent());

        assertThat(reactions.findAllFor("COMMENT", java.util.List.of(c.getId()))).isEmpty();
    }
}
