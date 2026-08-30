package com.platform.wikibackend.page;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageRepository;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 페이지 보관(W23). 휴지통은 "지웠다", 보관은 "끝났지만 남겨 둔다" — 트리·검색에서 빠지되
 * 링크로는 계속 열려야 한다. 그 두 가지가 이 기능의 전부라 테스트도 거기에 건다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ArchiveTest {

    private static final long EDITOR = 1L;
    private static final long VIEWER = 2L;

    @Autowired WebApplicationContext context;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired FakePermissionClient perms;

    MockMvc mvc;
    Long spaceId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestPages.deleteAll(jdbc);
        spaces.deleteAllInBatch();
        perms.reset();
        spaceId = spaces.save(Space.of("ar" + (System.nanoTime() % 100000), "보관", null, EDITOR)).getId();
        perms.allow(EDITOR, spaceId, WikiAction.VIEW);
        perms.allow(EDITOR, spaceId, WikiAction.EDIT);
        perms.allow(VIEWER, spaceId, WikiAction.VIEW);
    }

    @Test
    void 보관하면_트리에서_빠지지만_링크로는_열린다() throws Exception {
        Page page = pages.save(Page.of(spaceId, null, "지난 회고", "본문", EDITOR));

        mvc.perform(post("/api/wiki/pages/{id}/archive", page.getId()).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").isNotEmpty());

        mvc.perform(get("/api/wiki/spaces/{id}/pages/children", spaceId).with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/wiki/pages/{id}", page.getId()).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("지난 회고"));
    }

    @Test
    void 하위까지_함께_보관되고_목록에는_루트만_하위_수와_함께_뜬다() throws Exception {
        Page root = pages.save(Page.of(spaceId, null, "루트", "본문", EDITOR));
        pages.save(Page.of(spaceId, root.getId(), "하위", "본문", EDITOR));

        mvc.perform(post("/api/wiki/pages/{id}/archive", root.getId()).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isOk());

        mvc.perform(get("/api/wiki/spaces/{id}/archive", spaceId).with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("루트"))
                .andExpect(jsonPath("$[0].descendantCount").value(1));
    }

    /** 보관은 "끝났다"는 뜻이다 — 고치려면 먼저 해제해야 하고, 그 사실을 알려야 한다. */
    @Test
    void 보관된_문서는_편집할_수_없다() throws Exception {
        Page page = pages.save(Page.of(spaceId, null, "보관", "본문", EDITOR));
        mvc.perform(post("/api/wiki/pages/{id}/archive", page.getId()).with(asUser(EDITOR, "Alice")));

        mvc.perform(put("/api/wiki/pages/{id}", page.getId())
                        .with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"보관\",\"content\":\"고침\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("보관")));
    }

    @Test
    void 해제하면_원래_자리로_돌아온다() throws Exception {
        Page root = pages.save(Page.of(spaceId, null, "루트", "본문", EDITOR));
        Page child = pages.save(Page.of(spaceId, root.getId(), "하위", "본문", EDITOR));
        mvc.perform(post("/api/wiki/pages/{id}/archive", root.getId()).with(asUser(EDITOR, "Alice")));

        mvc.perform(post("/api/wiki/pages/{id}/unarchive", root.getId()).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").doesNotExist());

        assertThat(pages.findById(child.getId()).orElseThrow().isArchived()).isFalse();
        mvc.perform(get("/api/wiki/spaces/{id}/pages/children", spaceId).with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$[0].title").value("루트"));
    }

    /** 부모가 보관 중이면 자식만 풀어도 트리에 나타날 자리가 없다. */
    @Test
    void 상위가_보관_중이면_하위만_해제할_수_없다() throws Exception {
        Page root = pages.save(Page.of(spaceId, null, "루트", "본문", EDITOR));
        Page child = pages.save(Page.of(spaceId, root.getId(), "하위", "본문", EDITOR));
        mvc.perform(post("/api/wiki/pages/{id}/archive", root.getId()).with(asUser(EDITOR, "Alice")));

        mvc.perform(post("/api/wiki/pages/{id}/unarchive", child.getId()).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isConflict());
    }

    @Test
    void 보관은_편집_권한이_필요하다() throws Exception {
        Page page = pages.save(Page.of(spaceId, null, "문서", "본문", EDITOR));

        mvc.perform(post("/api/wiki/pages/{id}/archive", page.getId()).with(asUser(VIEWER, "Bob")))
                .andExpect(status().isForbidden());
    }

    /** 검색에서도 빠져야 한다 — 트리에서 뺀 이유와 같다(라이트 검색으로 확인). */
    @Test
    void 보관된_문서는_검색에_나오지_않는다() throws Exception {
        Page page = pages.save(Page.of(spaceId, null, "보관 문서", "고유한본문", EDITOR));
        mvc.perform(post("/api/wiki/pages/{id}/archive", page.getId()).with(asUser(EDITOR, "Alice")));

        String body = "{\"operationName\":\"S\",\"query\":\"query S($input: SearchInput!) { search(input: $input) { total } }\","
                + "\"variables\":{\"input\":{\"query\":\"고유한본문\"}}}";
        mvc.perform(post("/graphql").with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.data.search.total").value(0));
    }
}
