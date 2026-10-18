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

    // ── W-07: 보관 불변식은 모든 쓰기 경로에 걸린다 ──

    @Autowired com.platform.wikibackend.repository.PageRevisionRepository revisionRepo;
    @Autowired com.platform.wikibackend.repository.CollaborationDraftMetadataRepository drafts;
    @Autowired PageService pageService;

    private Page archived(String title) throws Exception {
        Page page = pages.save(Page.of(spaceId, null, title, "본문", EDITOR));
        revisionRepo.save(com.platform.wikibackend.domain.PageRevision.snapshotOf(page));
        mvc.perform(post("/api/wiki/pages/{id}/archive", page.getId()).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isOk());
        return page;
    }

    private void expectArchivedConflict(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        mvc.perform(request)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("보관")));
    }

    /**
     * 화면은 보관 문서를 읽기 전용으로 보여 주지만, 이력의 "이 버전으로 복원"이나 이미 열린 공동 편집 세션,
     * 메타 변경으로 계속 바뀌었다(감사 W-07). 보관 해제 전까지 변경이 없다는 전제가 감사·검증 배지의 바닥이다.
     */
    @Test
    void 보관된_문서는_어떤_쓰기_경로로도_바뀌지_않는다() throws Exception {
        Page page = archived("보관 문서");
        long id = page.getId();
        var alice = asUser(EDITOR, "Alice");

        expectArchivedConflict(post("/api/wiki/pages/{id}/revisions/{v}/restore", id, 1).with(alice));
        expectArchivedConflict(post("/api/wiki/pages/{id}/publish", id).with(alice));
        expectArchivedConflict(put("/api/wiki/pages/{id}/icon", id).with(alice)
                .contentType(MediaType.APPLICATION_JSON).content("{\"icon\":\"📌\"}"));
        expectArchivedConflict(put("/api/wiki/pages/{id}/owner", id).with(alice)
                .contentType(MediaType.APPLICATION_JSON).content("{\"ownerId\":1}"));
        expectArchivedConflict(put("/api/wiki/pages/{id}/verification", id).with(alice)
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        expectArchivedConflict(put("/api/wiki/pages/{id}/labels", id).with(alice)
                .contentType(MediaType.APPLICATION_JSON).content("{\"labels\":[\"새 라벨\"]}"));
        expectArchivedConflict(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/wiki/pages/{id}/attachments", id)
                .file(new org.springframework.mock.web.MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes()))
                .with(alice));
        expectArchivedConflict(post("/api/wiki/pages/{id}/move", id).with(alice)
                .contentType(MediaType.APPLICATION_JSON).content("{\"parentId\":null}"));

        Page after = pages.findById(id).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(1);
        assertThat(after.getIcon()).isNull();
        assertThat(after.getOwnerId()).isNull();
    }

    @Test
    void 보관된_문서의_공동_초안은_게시되지_않고_공동_편집_티켓도_나가지_않는다() throws Exception {
        Page page = archived("공동 편집 중 보관");
        drafts.save(com.platform.wikibackend.domain.CollaborationDraftMetadata.of(page.getId(), 1, 1));

        expectArchivedConflict(put("/api/wiki/pages/{id}/collaboration-draft", page.getId())
                .with(asUser(EDITOR, "Alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"t\",\"content\":\"c\",\"expectedPageVersion\":1,\"expectedGeneration\":1}"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> pageService.getEditable(EDITOR, page.getId()))
                .isInstanceOf(com.platform.common.error.ConflictException.class);
        assertThat(pages.findById(page.getId()).orElseThrow().getVersion()).isEqualTo(1);
    }

    @Test
    void 보관된_문서_아래로는_옮길_수_없다() throws Exception {
        Page box = archived("보관 상자");
        Page page = pages.save(Page.of(spaceId, null, "옮길 문서", "본문", EDITOR));

        expectArchivedConflict(post("/api/wiki/pages/{id}/move", page.getId()).with(asUser(EDITOR, "Alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"parentId\":" + box.getId() + "}"));
        assertThat(pages.findById(page.getId()).orElseThrow().getParentId()).isNull();
    }
}
