package com.platform.wikibackend.watch;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRestriction;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRestrictionRepository;
import com.platform.wikibackend.repository.PageWatchRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import com.platform.wikibackend.repository.SpaceWatchRepository;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 내 구독 목록과 일괄 해제(F-06). 권한을 잃은 대상은 행은 남기되 제목을 가린다. */
@SpringBootTest
@ActiveProfiles("test")
class WatchListTest {

    static final long ME = 1L;
    static final long OTHER = 2L;

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageWatchRepository pageWatches;
    @Autowired SpaceWatchRepository spaceWatches;
    @Autowired PageRestrictionRepository restrictions;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    MockMvc mvc;
    Space space;
    Space other;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        pageWatches.deleteAll();
        spaceWatches.deleteAll();
        restrictions.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        space = spaces.save(Space.of("wl" + (System.nanoTime() % 100000), "구독", null, ME));
        other = spaces.save(Space.of("wo" + (System.nanoTime() % 100000), "다른", null, ME));
        perms.allow(ME, space.getId(), WikiAction.VIEW);
        perms.allow(ME, other.getId(), WikiAction.VIEW);
    }

    private long watchedPage(String title) throws Exception {
        Page page = pages.save(Page.of(space.getId(), null, title, "본문", OTHER));
        mvc.perform(post("/api/wiki/pages/{id}/watch", page.getId()).with(asUser(ME, "Me")))
                .andExpect(status().is2xxSuccessful());
        return page.getId();
    }

    @Test
    void 구독한_문서와_스페이스가_종류별로_나오고_권한을_잃은_문서는_제목을_가린다() throws Exception {
        long open = watchedPage("보이는 문서");
        long hidden = watchedPage("잠긴 문서");
        mvc.perform(put("/api/wiki/spaces/{id}/watch", other.getId()).with(asUser(ME, "Me")))
                .andExpect(status().is2xxSuccessful());
        restrictions.save(PageRestriction.of(hidden, PageRestriction.Type.VIEW,
                PageRestriction.PrincipalType.USER, OTHER, OTHER));

        mvc.perform(get("/api/wiki/watches").with(asUser(ME, "Me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages.length()").value(2))
                .andExpect(jsonPath("$.pages[?(@.pageId == " + open + ")].title").value("보이는 문서"))
                .andExpect(jsonPath("$.pages[?(@.pageId == " + open + ")].hidden").value(false))
                .andExpect(jsonPath("$.pages[?(@.pageId == " + hidden + ")].title").value(org.hamcrest.Matchers.contains((Object) null)))
                .andExpect(jsonPath("$.pages[?(@.pageId == " + hidden + ")].hidden").value(true))
                .andExpect(jsonPath("$.spaces.length()").value(1))
                .andExpect(jsonPath("$.spaces[0].name").value("다른"));
    }

    @Test
    void 여러_개를_한_번에_해제한다_볼_수_없게_된_것도() throws Exception {
        long open = watchedPage("보이는 문서");
        long hidden = watchedPage("잠긴 문서");
        mvc.perform(put("/api/wiki/spaces/{id}/watch", other.getId()).with(asUser(ME, "Me")));
        restrictions.save(PageRestriction.of(hidden, PageRestriction.Type.VIEW,
                PageRestriction.PrincipalType.USER, OTHER, OTHER));

        mvc.perform(post("/api/wiki/watches/unwatch").with(asUser(ME, "Me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageIds\":[" + open + "," + hidden + "],\"spaceIds\":[" + other.getId() + "]}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/wiki/watches").with(asUser(ME, "Me")))
                .andExpect(jsonPath("$.pages.length()").value(0))
                .andExpect(jsonPath("$.spaces.length()").value(0));
        mvc.perform(get("/api/wiki/pages/{id}/watch", open).with(asUser(ME, "Me")))
                .andExpect(jsonPath("$.watching").value(false));
        assertThat(pageWatches.findAll()).isEmpty();
    }
}
