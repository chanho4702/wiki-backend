package com.platform.wikibackend.template;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageTemplateRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 페이지 템플릿(W23).
 *
 * 읽기는 스페이스 VIEW, 쓰기는 ADMIN이다 — 템플릿은 그 스페이스의 모든 사람이 새 문서를 만들 때
 * 마주치는 공용 자산이라, 편집 권한자 아무나 바꾸면 팀의 문서 형식이 조용히 흔들린다.
 */
@SpringBootTest
@ActiveProfiles("test")
class TemplateApiTest {

    private static final long ADMIN = 1L;
    private static final long EDITOR = 2L;

    @Autowired WebApplicationContext context;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageTemplateRepository templates;
    @Autowired FakePermissionClient perms;

    MockMvc mvc;
    Long spaceId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        templates.deleteAllInBatch();
        TestPages.deleteAll(jdbc);
        spaces.deleteAllInBatch();
        perms.reset();

        spaceId = spaces.save(Space.of("tpl", "템플릿", null, ADMIN)).getId();
        perms.allow(ADMIN, spaceId, WikiAction.VIEW);
        perms.allow(ADMIN, spaceId, WikiAction.EDIT);
        perms.allow(ADMIN, spaceId, WikiAction.ADMIN);
        perms.allow(EDITOR, spaceId, WikiAction.VIEW);
        perms.allow(EDITOR, spaceId, WikiAction.EDIT);
    }

    @Test
    void 템플릿을_만들고_목록에서_본문까지_받는다() throws Exception {
        create("회의록", "## 참석자\n\n## 결정\n");

        mvc.perform(get("/api/wiki/spaces/{id}/templates", spaceId).with(asUser(EDITOR, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("회의록"))
                // 목록에서도 본문을 준다 — 미리보기가 곧 선택 근거다
                .andExpect(jsonPath("$[0].content").value("## 참석자\n\n## 결정\n"));
    }

    /** 편집 권한만으로 팀의 문서 형식을 바꿀 수 있으면 안 된다. */
    @Test
    void 쓰기는_ADMIN만_할_수_있다() throws Exception {
        mvc.perform(post("/api/wiki/spaces/{id}/templates", spaceId)
                        .with(asUser(EDITOR, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"회의록\",\"content\":\"본문\"}"))
                .andExpect(status().isForbidden());

        long id = create("회의록", "본문");
        mvc.perform(delete("/api/wiki/templates/{id}", id).with(asUser(EDITOR, "Bob")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 같은_이름의_템플릿은_거부한다() throws Exception {
        create("회의록", "본문");

        mvc.perform(post("/api/wiki/spaces/{id}/templates", spaceId)
                        .with(asUser(ADMIN, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  회의록  \",\"content\":\"다른 본문\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("이미 있습니다")));
    }

    /** 앞뒤·연속 공백만 다른 이름은 화면에서 구분되지 않는다 — 정규화해서 같은 이름으로 본다. */
    @Test
    void 이름의_공백을_정규화한다() throws Exception {
        long id = create("  주간   회의록 ", "본문");

        assertThat(templates.findById(id).orElseThrow().getName()).isEqualTo("주간 회의록");
    }

    @Test
    void 빈_이름은_거부한다() throws Exception {
        mvc.perform(post("/api/wiki/spaces/{id}/templates", spaceId)
                        .with(asUser(ADMIN, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \",\"content\":\"본문\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 수정하면_이름과_본문이_바뀐다() throws Exception {
        long id = create("회의록", "본문");

        mvc.perform(put("/api/wiki/templates/{id}", id)
                        .with(asUser(ADMIN, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"주간 회의록\",\"content\":\"새 본문\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("주간 회의록"))
                .andExpect(jsonPath("$.content").value("새 본문"));
    }

    /**
     * 템플릿을 처음부터 쓰는 사람은 드물다 — 이미 잘 쓴 문서 하나가 곧 형식이다.
     * 본문만 가져온다: 제목까지 가져오면 그 템플릿으로 만든 문서마다 같은 제목이 붙는다.
     */
    @Test
    void 페이지를_템플릿으로_저장한다() throws Exception {
        Page page = pages.save(Page.of(spaceId, null, "2026-08 회고", "## 잘한 것\n## 아쉬운 것\n", ADMIN));

        mvc.perform(post("/api/wiki/pages/{id}/save-as-template", page.getId())
                        .with(asUser(ADMIN, "Alice")))
                .andExpect(status().isCreated())
                // 이름을 안 주면 그 페이지 제목을 쓴다
                .andExpect(jsonPath("$.name").value("2026-08 회고"))
                .andExpect(jsonPath("$.content").value("## 잘한 것\n## 아쉬운 것\n"));
    }

    @Test
    void 삭제하면_목록에서_사라진다() throws Exception {
        long id = create("회의록", "본문");

        mvc.perform(delete("/api/wiki/templates/{id}", id).with(asUser(ADMIN, "Alice")))
                .andExpect(status().isNoContent());

        assertThat(templates.findBySpaceIdOrderByNameAsc(spaceId)).isEmpty();
    }

    @Test
    void 볼_수_없는_스페이스의_템플릿은_읽을_수_없다() throws Exception {
        create("회의록", "본문");

        mvc.perform(get("/api/wiki/spaces/{id}/templates", spaceId).with(asUser(99L, "Stranger")))
                .andExpect(status().isForbidden());
    }

    private long create(String name, String content) throws Exception {
        String body = mvc.perform(post("/api/wiki/spaces/{id}/templates", spaceId)
                        .with(asUser(ADMIN, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new com.fasterxml.jackson.databind.ObjectMapper()
                                .writeValueAsString(java.util.Map.of("name", name, "content", content))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
    }
}
