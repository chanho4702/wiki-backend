package com.platform.wikibackend.space;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.permission.FakePermissionClient;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 개인 스페이스(W23). 팀 스페이스 한구석에 "OO 작업 메모" 폴더가 생기던 문제 — 개인 스페이스도
 * 스페이스다(권한·트리·검색이 같다). 다른 점은 "누구의 것인가"뿐이고 한 사람에 하나다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PersonalSpaceTest {

    @Autowired WebApplicationContext context;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired SpaceRepository spaces;
    @Autowired FakePermissionClient perms;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestPages.deleteAll(jdbc);
        spaces.deleteAllInBatch();
        perms.reset();
    }

    @Test
    void 처음_부르면_만들고_주인에게_ADMIN을_준다() throws Exception {
        mvc.perform(post("/api/wiki/spaces/personal").with(asUser(7L, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(7))
                .andExpect(jsonPath("$.name").value("Alice의 스페이스"))
                .andExpect(jsonPath("$.key").value("me-7"));

        assertThat(perms.grantedAdmins).anySatisfy(g -> assertThat(g[0]).isEqualTo(7L));
        // 만든 뒤에는 접근 가능한 목록에 보인다
        mvc.perform(get("/api/wiki/spaces").with(asUser(7L, "Alice")))
                .andExpect(jsonPath("$[?(@.ownerId == 7)]").exists());
    }

    /** 한 사람에 하나 — 두 번 불러도 같은 스페이스다. */
    @Test
    void 다시_부르면_같은_스페이스를_준다() throws Exception {
        String first = mvc.perform(post("/api/wiki/spaces/personal").with(asUser(7L, "Alice")))
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/wiki/spaces/personal").with(asUser(7L, "Alice")))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(spaces.findAll().stream().filter(s -> s.isPersonal()).count()).isEqualTo(1);
    }

    /** 남의 개인 스페이스는 권한이 없어 목록에 없다 — 개인 스페이스도 일반 권한 규칙을 탄다. */
    @Test
    void 남의_개인_스페이스는_보이지_않는다() throws Exception {
        mvc.perform(post("/api/wiki/spaces/personal").with(asUser(7L, "Alice")));

        mvc.perform(get("/api/wiki/spaces").with(asUser(8L, "Bob")))
                .andExpect(jsonPath("$[?(@.ownerId == 7)]").doesNotExist());
    }
}
