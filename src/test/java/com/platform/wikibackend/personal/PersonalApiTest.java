package com.platform.wikibackend.personal;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRestriction;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRestrictionRepository;
import com.platform.wikibackend.repository.PageStarRepository;
import com.platform.wikibackend.repository.PageVisitRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import com.platform.wikibackend.repository.SpaceStarRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 별표·최근 방문의 서버 이전(W23).
 *
 * 지금까지 브라우저 localStorage에만 있어서 기기 간 동기화가 없었고, 브라우저 데이터를 지우면
 * 통째로 사라졌다. 서버로 옮기면서 중요한 것은 **읽을 때마다 지금 권한으로 다시 거른다**는
 * 점이다 — 별표해 둔 뒤 권한이 회수될 수 있고, 그때 목록에 제목이 남으면 그것만으로 샌다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PersonalApiTest {

    private static final long ME = 1L;
    private static final long OTHER = 2L;

    @Autowired WebApplicationContext context;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageStarRepository pageStars;
    @Autowired SpaceStarRepository spaceStars;
    @Autowired PageVisitRepository visits;
    @Autowired PageRestrictionRepository restrictions;
    @Autowired FakePermissionClient perms;

    MockMvc mvc;
    Space space;
    Page page;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        pageStars.deleteAllInBatch();
        spaceStars.deleteAllInBatch();
        visits.deleteAllInBatch();
        restrictions.deleteAllInBatch();
        TestPages.deleteAll(jdbc);
        spaces.deleteAllInBatch();
        perms.reset();

        space = spaces.save(Space.of("ps" + (System.nanoTime() % 100000), "개인", null, ME));
        perms.allow(ME, space.getId(), WikiAction.VIEW);
        page = pages.save(Page.of(space.getId(), null, "문서", "본문", ME));
    }

    @Test
    void 페이지_별표를_켜고_끈다() throws Exception {
        mvc.perform(put("/api/wiki/pages/{id}/star", page.getId()).with(asUser(ME, "Me")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/wiki/stars").with(asUser(ME, "Me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages.length()").value(1))
                .andExpect(jsonPath("$.pages[0].page.title").value("문서"))
                .andExpect(jsonPath("$.pages[0].spaceName").value("개인"));

        mvc.perform(delete("/api/wiki/pages/{id}/star", page.getId()).with(asUser(ME, "Me")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/wiki/stars").with(asUser(ME, "Me")))
                .andExpect(jsonPath("$.pages.length()").value(0));
    }

    /** 같은 문서를 두 번 별표해도 한 줄이다 — 두 번 눌렀다고 목록에 두 번 나오면 안 된다. */
    @Test
    void 별표는_멱등이다() throws Exception {
        mvc.perform(put("/api/wiki/pages/{id}/star", page.getId()).with(asUser(ME, "Me")));
        mvc.perform(put("/api/wiki/pages/{id}/star", page.getId()).with(asUser(ME, "Me")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/wiki/stars").with(asUser(ME, "Me")))
                .andExpect(jsonPath("$.pages.length()").value(1));
    }

    @Test
    void 별표는_사용자마다_따로다() throws Exception {
        perms.allow(OTHER, space.getId(), WikiAction.VIEW);
        mvc.perform(put("/api/wiki/pages/{id}/star", page.getId()).with(asUser(ME, "Me")));

        mvc.perform(get("/api/wiki/stars").with(asUser(OTHER, "Other")))
                .andExpect(jsonPath("$.pages.length()").value(0));
    }

    /** 별표해 둔 뒤 제한이 걸리면 목록에서도 사라져야 한다 — 제목만 남아도 그것이 누출이다. */
    @Test
    void 볼_수_없게_된_문서는_별표_목록에서_빠진다() throws Exception {
        perms.allow(OTHER, space.getId(), WikiAction.VIEW);
        mvc.perform(put("/api/wiki/pages/{id}/star", page.getId()).with(asUser(OTHER, "Other")))
                .andExpect(status().isNoContent());

        restrictions.save(PageRestriction.of(page.getId(), PageRestriction.Type.VIEW,
                PageRestriction.PrincipalType.USER, ME, ME));

        mvc.perform(get("/api/wiki/stars").with(asUser(OTHER, "Other")))
                .andExpect(jsonPath("$.pages.length()").value(0));
    }

    /** 볼 수 없게 된 문서도 별표는 뗄 수 있어야 한다 — 아니면 목록에서 지울 방법이 없다. */
    @Test
    void 볼_수_없게_된_문서의_별표도_해제된다() throws Exception {
        pageStars.save(com.platform.wikibackend.domain.PageStar.of(OTHER, page.getId()));

        mvc.perform(delete("/api/wiki/pages/{id}/star", page.getId()).with(asUser(OTHER, "Other")))
                .andExpect(status().isNoContent());

        assertThat(pageStars.findPageIds(OTHER)).isEmpty();
    }

    @Test
    void 스페이스_별표도_켜고_끈다() throws Exception {
        mvc.perform(put("/api/wiki/spaces/{id}/star", space.getId()).with(asUser(ME, "Me")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/wiki/stars").with(asUser(ME, "Me")))
                .andExpect(jsonPath("$.spaceIds.length()").value(1))
                .andExpect(jsonPath("$.spaceIds[0]").value(String.valueOf(space.getId())));

        mvc.perform(delete("/api/wiki/spaces/{id}/star", space.getId()).with(asUser(ME, "Me")));
        mvc.perform(get("/api/wiki/stars").with(asUser(ME, "Me")))
                .andExpect(jsonPath("$.spaceIds.length()").value(0));
    }

    /** 방문 기록은 조회수 증가와 같은 요청에서 남는다 — 왕복을 늘리지 않는다. */
    @Test
    void 페이지를_보면_최근_목록에_쌓인다() throws Exception {
        mvc.perform(post("/api/wiki/pages/{id}/views", page.getId()).with(asUser(ME, "Me")))
                .andExpect(status().isOk());

        mvc.perform(get("/api/wiki/recent").with(asUser(ME, "Me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].page.title").value("문서"));
    }

    /** 같은 문서를 다시 봐도 줄이 늘지 않는다 — 필요한 것은 "마지막으로 언제 봤나"뿐이다. */
    @Test
    void 다시_방문해도_한_줄이다() throws Exception {
        mvc.perform(post("/api/wiki/pages/{id}/views", page.getId()).with(asUser(ME, "Me")));
        mvc.perform(post("/api/wiki/pages/{id}/views", page.getId()).with(asUser(ME, "Me")));

        mvc.perform(get("/api/wiki/recent").with(asUser(ME, "Me")))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void 최근_목록은_최신순이다() throws Exception {
        Page second = pages.save(Page.of(space.getId(), null, "두 번째", "본문", ME));
        mvc.perform(post("/api/wiki/pages/{id}/views", page.getId()).with(asUser(ME, "Me")));
        mvc.perform(post("/api/wiki/pages/{id}/views", second.getId()).with(asUser(ME, "Me")));

        mvc.perform(get("/api/wiki/recent").with(asUser(ME, "Me")))
                .andExpect(jsonPath("$[0].page.title").value("두 번째"));
    }

    @Test
    void 별표_목록은_인증이_필요하다() throws Exception {
        mvc.perform(get("/api/wiki/stars")).andExpect(status().isUnauthorized());
    }
}
