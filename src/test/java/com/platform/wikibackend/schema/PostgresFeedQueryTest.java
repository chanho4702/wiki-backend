package com.platform.wikibackend.schema;

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
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 문서 피드 질의(사람별·인기·팔로잉)가 실제 PostgreSQL에서 도는가 — 불리언 파라미터와 in 절을 묶은 스페이스
 * 범위 조건, 구독 서브쿼리, V44 부분 인덱스. 결과 의미(정렬·가시성)는 H2의 FeedApiTest가 맡는다.
 * 다른 테스트의 행이 같은 DB에 있으므로 "내 스페이스의 문서가 들어 있다"만 본다.
 */
class PostgresFeedQueryTest extends PostgresTestSupport {

    private static final AtomicInteger SEQ = new AtomicInteger();
    static final long ALICE = 41L;
    static final long ROOT = 42L; // GLOBAL — 스페이스 목록 없이(allSpaces=true) 질의되는 갈래

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
        space = spaces.save(Space.of("pgfeed" + SEQ.incrementAndGet(), "PG 피드", null, ALICE));
        perms.allow(ALICE, space.getId(), WikiAction.VIEW);
        perms.allow(ALICE, space.getId(), WikiAction.EDIT);
        perms.allowAllSpaces(ROOT);
    }

    @Test
    void 세_피드가_스페이스_목록과_전역_범위_양쪽에서_돈다() throws Exception {
        long page = create("PG 피드 문서");
        jdbc.update("update page set view_count = 7 where id = ?", page);
        mvc.perform(put("/api/wiki/spaces/{id}/watch", space.getId()).with(asUser(ALICE, "앨리스")))
                .andExpect(status().isOk());

        for (long caller : new long[] {ALICE, ROOT}) {
            mvc.perform(get("/api/wiki/users/{id}/pages", ALICE).with(asUser(caller, "u")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].page.id", hasItem((int) page)));
            mvc.perform(get("/api/wiki/feed/popular").param("limit", "50").with(asUser(caller, "u")))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/wiki/feed/following").with(asUser(ALICE, "앨리스")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].page.id", hasItem((int) page)));
        mvc.perform(get("/api/wiki/feed/following").with(asUser(ROOT, "루트")))
                .andExpect(status().isOk());
    }

    @Test
    void V44_피드_인덱스가_있다() {
        assertThat(jdbc.queryForList(
                "select indexname from pg_indexes where tablename = 'page'", String.class))
                .contains("idx_page_created_by_recent", "idx_page_updated_by_recent", "idx_page_published_recent");
    }

    private long create(String title) throws Exception {
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId() + ",\"title\":\"" + title
                                + "\",\"content\":\"본문\",\"type\":\"page\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }
}
