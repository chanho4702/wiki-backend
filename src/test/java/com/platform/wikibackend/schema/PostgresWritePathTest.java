package com.platform.wikibackend.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.wikibackend.PostgresTestSupport;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.concurrent.atomic.AtomicInteger;

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
 * 핵심 저장 경로 스모크 — HTTP 계약 그대로 실제 PostgreSQL 스키마(Flyway) 위에서 돈다.
 *
 * H2 스위트가 같은 경로를 이미 검증하지만, 거기서는 CHECK·FK·네이티브 쿼리 타입이 없다.
 * 여기서는 "운영 스키마가 이 쓰기를 받아 주는가"만 본다 — 결과 의미는 H2 쪽 테스트가 맡는다.
 */
class PostgresWritePathTest extends PostgresTestSupport {

    private static final AtomicInteger SEQ = new AtomicInteger();
    static final long ALICE = 1L;

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired FakePermissionClient perms;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    MockMvc mvc;
    Space space;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        perms.reset();
        space = spaces.save(Space.of("pgw" + SEQ.incrementAndGet(), "PG 쓰기", null, ALICE));
        perms.allow(ALICE, space.getId(), WikiAction.VIEW);
        perms.allow(ALICE, space.getId(), WikiAction.EDIT);
        perms.allow(ALICE, space.getId(), WikiAction.ADMIN);
    }

    @Test
    void PAGE_FOLDER_BLOG를_만들고_읽는다() throws Exception {
        long folder = create("folder", null, "폴더", "");
        long page = create("page", folder, "문서", "본문");
        long blog = create("blog", null, "소식", "블로그 본문");

        assertThat(jdbc.queryForObject("select type from page where id = ?", String.class, folder)).isEqualTo("FOLDER");
        assertThat(jdbc.queryForObject("select type from page where id = ?", String.class, page)).isEqualTo("PAGE");
        assertThat(jdbc.queryForObject("select type from page where id = ?", String.class, blog)).isEqualTo("BLOG");

        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/blog").with(asUser(ALICE, "앨리스")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(blog));
    }

    @Test
    void 초안_블로그도_저장되고_게시된다() throws Exception {
        long blog = create("blog", null, "초안 소식", "본문", "draft");
        mvc.perform(post("/api/wiki/pages/" + blog + "/publish").with(asUser(ALICE, "앨리스")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("published"));
    }

    @Test
    void 수정은_새_버전과_리비전을_남긴다() throws Exception {
        long page = create("page", null, "작업 문서", "- [ ] 첫 작업");
        update(page, "작업 문서", "- [ ] 첫 작업\\n- [ ] 둘째 작업", 1);

        assertThat(jdbc.queryForObject("select version from page where id = ?", Integer.class, page)).isEqualTo(2);
        assertThat(count("page_revision", "page_id", page)).isEqualTo(2);
        assertThat(count("page_task", "page_id", page)).isEqualTo(2); // 일반 저장도 작업 표를 다시 만든다(W-01)
    }

    /**
     * "지우고 다시 넣기"로 파생 표를 교체하는 경로들. Hibernate는 flush 때 INSERT를 DELETE보다 먼저
     * 보내므로, 같은 키가 남는 교체는 유니크 제약에 걸린다 — H2(create-drop)에는 그 제약이 없어 안 보였다.
     */
    @Test
    void 작업_토글은_같은_줄_번호의_작업_행을_다시_만든다() throws Exception {
        long page = create("page", null, "토글", "- [ ] 첫 작업\\n- [ ] 둘째 작업");
        mvc.perform(put("/api/wiki/pages/" + page + "/tasks/1").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"done\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true));
        assertThat(count("page_task", "page_id", page)).isEqualTo(2);
    }

    @Test
    void 제한_교체는_기존_주체를_남긴_채_더할_수_있다() throws Exception {
        long page = create("page", null, "제한", "본문");
        restrict(page, "[{\"type\":\"USER\",\"id\":1}]");
        restrict(page, "[{\"type\":\"USER\",\"id\":1},{\"type\":\"USER\",\"id\":2}]");
        assertThat(count("page_restriction", "page_id", page)).isEqualTo(2);
    }

    @Test
    void 스페이스_라벨_교체는_기존_라벨을_남긴_채_더할_수_있다() throws Exception {
        spaceLabels("[\"운영\"]");
        spaceLabels("[\"운영\",\"배포\"]");
        assertThat(count("space_label", "space_id", space.getId())).isEqualTo(2);
    }

    @Test
    void 문서_라벨_교체도_같다() throws Exception {
        long page = create("page", null, "라벨", "본문");
        label(page, "운영");
        mvc.perform(put("/api/wiki/pages/" + page + "/labels").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":[\"운영\",\"배포\"]}"))
                .andExpect(status().isOk());
        assertThat(count("page_label", "page_id", page)).isEqualTo(2);
    }

    @Test
    void 휴지통_삭제와_영구_삭제가_딸린_행까지_지운다() throws Exception {
        long parent = create("page", null, "부모", "- [ ] 할 일 [[자식]]");
        long child = create("page", parent, "자식", "본문");
        label(parent, "배포");
        comment(parent, "댓글");

        mvc.perform(delete("/api/wiki/pages/" + parent).param("children", "cascade").with(asUser(ALICE, "앨리스")))
                .andExpect(status().is2xxSuccessful());
        assertThat(jdbc.queryForObject("select count(*) from page where id in (?, ?) and deleted_at is not null",
                Integer.class, parent, child)).isEqualTo(2);

        mvc.perform(delete("/api/wiki/pages/" + parent + "/purge").with(asUser(ALICE, "앨리스")))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from page where id in (?, ?)", Integer.class, parent, child))
                .isZero();
        for (String table : new String[] {"page_revision", "page_task", "page_label", "page_comment", "page_watch"}) {
            assertThat(count(table, "page_id", parent)).as(table).isZero();
        }
        assertThat(count("page_link", "source_page_id", parent)).isZero();
    }

    @Test
    void 스페이스_삭제가_문서와_딸린_행을_함께_지운다() throws Exception {
        long parent = create("page", null, "부모", "- [ ] 할 일");
        long child = create("page", parent, "자식", "본문");
        create("blog", null, "소식", "본문");
        label(child, "운영");
        comment(child, "댓글");
        // 휴지통 행도 함께 사라져야 한다
        long trashed = create("page", null, "버린 문서", "본문");
        mvc.perform(delete("/api/wiki/pages/" + trashed).with(asUser(ALICE, "앨리스")))
                .andExpect(status().is2xxSuccessful());

        mvc.perform(delete("/api/wiki/spaces/" + space.getId()).with(asUser(ALICE, "앨리스")))
                .andExpect(status().isNoContent());

        assertThat(count("page", "space_id", space.getId())).isZero();
        assertThat(count("space", "id", space.getId())).isZero();
        assertThat(count("page_revision", "page_id", child)).isZero();
        assertThat(count("page_comment", "page_id", child)).isZero();
    }

    // ── 도우미 ──

    long create(String type, Long parentId, String title, String content) throws Exception {
        return create(type, parentId, title, content, "published");
    }

    long create(String type, Long parentId, String title, String content, String status) throws Exception {
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId() + ",\"parentId\":" + parentId
                                + ",\"title\":\"" + title + "\",\"content\":\"" + content
                                + "\",\"type\":\"" + type + "\",\"status\":\"" + status + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        return node.path("id").asLong();
    }

    void update(long pageId, String title, String content, int expectedVersion) throws Exception {
        Long parent = jdbc.queryForObject("select parent_id from page where id = ?", Long.class, pageId);
        mvc.perform(put("/api/wiki/pages/" + pageId).with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"content\":\"" + content
                                + "\",\"parentId\":" + parent + ",\"expectedVersion\":" + expectedVersion + "}"))
                .andExpect(status().isOk());
    }

    void label(long pageId, String name) throws Exception {
        mvc.perform(put("/api/wiki/pages/" + pageId + "/labels").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":[\"" + name + "\"]}"))
                .andExpect(status().isOk());
    }

    void restrict(long pageId, String viewPrincipals) throws Exception {
        mvc.perform(put("/api/wiki/pages/" + pageId + "/restrictions").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"view\":" + viewPrincipals + ",\"edit\":[]}"))
                .andExpect(status().isOk());
    }

    void spaceLabels(String labels) throws Exception {
        mvc.perform(put("/api/wiki/spaces/" + space.getId() + "/labels").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":" + labels + "}"))
                .andExpect(status().isOk());
    }

    void comment(long pageId, String body) throws Exception {
        mvc.perform(post("/api/wiki/pages/" + pageId + "/comments").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + body + "\"}"))
                .andExpect(status().is2xxSuccessful());
    }

    int count(String table, String column, long id) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ?", Integer.class, id);
    }
}
