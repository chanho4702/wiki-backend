package com.platform.wikibackend.page;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 구조 변경 경로의 질의 수가 서브트리·스페이스 크기에 비례하지 않는다(감사 W-15).
 *
 * collectSubtree가 노드마다 findById를, 스페이스 삭제가 페이지마다 질의 5회를 돌렸다. 스페이스 잠금을
 * 쥔 채 수천 번 왕복하면 같은 스페이스의 다른 편집이 줄을 선다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
class StructureQueryScaleTest {

    static final long USER = 1L;

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("delete from page_revision");
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private Space space(String key) {
        Space space = spaces.save(Space.of(key, key, null, USER));
        perms.allow(USER, space.getId(), WikiAction.VIEW);
        perms.allow(USER, space.getId(), WikiAction.EDIT);
        perms.allow(USER, space.getId(), WikiAction.ADMIN);
        return space;
    }

    /** 옮길 문서 하나 + 그 아래 일렬 체인 depth개(서브트리 크기). 형제 수는 같다. */
    private long[] subtree(Space space, int depth) {
        Page target = pages.save(Page.of(space.getId(), null, "옮길 문서", "본문", USER));
        Page newParent = pages.save(Page.of(space.getId(), null, "새 부모", "본문", USER));
        long parent = target.getId();
        for (int i = 0; i < depth; i++) {
            parent = pages.save(Page.of(space.getId(), parent, "하위 " + i, "본문", USER)).getId();
        }
        return new long[] {target.getId(), newParent.getId()};
    }

    private long queriesForMove(long pageId, long newParentId) throws Exception {
        Statistics stats = statistics();
        stats.clear();
        mvc.perform(post("/api/wiki/pages/{id}/move", pageId).with(asUser(USER, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + newParentId + ",\"confirmImpact\":true}"))
                .andExpect(status().isOk());
        return stats.getPrepareStatementCount();
    }

    @Test
    void 이동의_질의_수는_서브트리_크기와_무관하다() throws Exception {
        long[] small = subtree(space("mvsmall"), 3);
        long[] large = subtree(space("mvlarge"), 60);

        long smallCount = queriesForMove(small[0], small[1]);
        long largeCount = queriesForMove(large[0], large[1]);

        assertThat(largeCount).isEqualTo(smallCount);
    }

    private long queriesForSpaceDelete(Space space) throws Exception {
        Statistics stats = statistics();
        stats.clear();
        mvc.perform(delete("/api/wiki/spaces/{id}", space.getId()).with(asUser(USER, "Alice")))
                .andExpect(status().isNoContent());
        return stats.getPrepareStatementCount();
    }

    @Test
    void 스페이스_삭제의_질의_수는_문서_수와_무관하다() throws Exception {
        Space small = space("delsmall");
        Space large = space("dellarge");
        subtree(small, 3);
        subtree(large, 60);

        long smallCount = queriesForSpaceDelete(small);
        long largeCount = queriesForSpaceDelete(large);

        assertThat(largeCount).isEqualTo(smallCount);
        assertThat(pages.findAll()).isEmpty();
    }
}
