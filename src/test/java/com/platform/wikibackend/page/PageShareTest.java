package com.platform.wikibackend.page;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRestriction;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.NotificationRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRestrictionRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 페이지 공유(W23). "이 문서 봐주세요"를 전할 방법이 없었다 — 링크를 메신저에 붙이거나
 * 본문에 멘션을 억지로 넣어야 했다. 공유는 알림의 한 종류(SHARED)다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PageShareTest {

    private static final long ME = 1L;
    private static final long YOU = 2L;
    private static final long STRANGER = 3L;

    @Autowired WebApplicationContext context;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired NotificationRepository notifications;
    @Autowired PageRestrictionRepository restrictions;
    @Autowired FakePermissionClient perms;

    MockMvc mvc;
    Space space;
    Page page;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        notifications.deleteAllInBatch();
        restrictions.deleteAllInBatch();
        TestPages.deleteAll(jdbc);
        spaces.deleteAllInBatch();
        perms.reset();

        space = spaces.save(Space.of("sh" + (System.nanoTime() % 100000), "공유", null, ME));
        perms.allow(ME, space.getId(), WikiAction.VIEW);
        perms.allow(YOU, space.getId(), WikiAction.VIEW);
        page = pages.save(Page.of(space.getId(), null, "봐주세요", "본문", ME));
    }

    @Test
    void 공유하면_수신자_알림함에_메모와_함께_뜬다() throws Exception {
        mvc.perform(post("/api/wiki/pages/{id}/share", page.getId())
                        .with(asUser(ME, "Me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[2],\"note\":\"검토 부탁\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(1));

        mvc.perform(get("/api/wiki/notifications").with(asUser(YOU, "You")))
                .andExpect(jsonPath("$.items[0].type").value("SHARED"))
                .andExpect(jsonPath("$.items[0].note").value("검토 부탁"))
                .andExpect(jsonPath("$.items[0].actorId").value(ME))
                .andExpect(jsonPath("$.items[0].pageTitle").value("봐주세요"));
    }

    /** 공유했다고 권한이 생기지는 않는다 — 볼 수 없는 수신자는 조용히 빠지고 전달 수로 드러난다. */
    @Test
    void 볼_수_없는_수신자에게는_전달되지_않는다() throws Exception {
        restrictions.save(PageRestriction.of(page.getId(), PageRestriction.Type.VIEW,
                PageRestriction.PrincipalType.USER, ME, ME));

        mvc.perform(post("/api/wiki/pages/{id}/share", page.getId())
                        .with(asUser(ME, "Me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[2,3]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(0));
    }

    /** 같은 문서를 두 번 보냈으면 둘 다 의미 있다 — 합치지 않는다(멘션과 같은 규칙). */
    @Test
    void 두_번_공유하면_알림도_두_건이다() throws Exception {
        for (String note : new String[] {"1차", "2차"}) {
            mvc.perform(post("/api/wiki/pages/{id}/share", page.getId())
                    .with(asUser(ME, "Me"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"userIds\":[2],\"note\":\"" + note + "\"}"));
        }

        mvc.perform(get("/api/wiki/notifications").with(asUser(YOU, "You")))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void 받는_사람이_없으면_거부한다() throws Exception {
        mvc.perform(post("/api/wiki/pages/{id}/share", page.getId())
                        .with(asUser(ME, "Me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 볼_수_없는_사람은_공유할_수_없다() throws Exception {
        mvc.perform(post("/api/wiki/pages/{id}/share", page.getId())
                        .with(asUser(STRANGER, "Stranger"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[2]}"))
                .andExpect(status().isForbidden());
    }
}
