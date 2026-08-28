package com.platform.wikibackend.permission;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRestriction;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRestrictionRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.platform.wikibackend.TestPages;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 규모 회귀 가드(2026-08-28).
 *
 * 페이지 한 장을 여는 비용이 **스페이스 크기에 비례하면 안 된다.** 예전 구현은 제한 판정을 위해
 * 스페이스의 전 페이지로 부모 맵을 만들었고, 문서가 수만 건이 되면 조회 한 번이 전량 스캔이었다.
 * 지금은 대상의 조상 폐포만 읽는다 — 이 테스트가 그 성질을 고정한다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
class PermissionQueryScaleTest {

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRestrictionRepository restrictions;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;
    MockMvc mvc;

    static final long USER = 1L;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        restrictions.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    /** 루트 하나 + 그 아래 형제 n개. 대상은 항상 첫 자식이라 조상 체인 길이는 같다. */
    private long buildSpace(String key, int siblings) {
        Space space = spaces.save(Space.of(key, key, null, USER));
        perms.allow(USER, space.getId(), WikiAction.VIEW);
        perms.allow(USER, space.getId(), WikiAction.EDIT);
        Page root = pages.save(Page.of(space.getId(), null, "루트", "본문", USER));
        long target = 0;
        for (int i = 0; i < siblings; i++) {
            Page child = pages.save(Page.of(space.getId(), root.getId(), "자식 " + i, "본문", USER));
            if (i == 0) target = child.getId();
        }
        // 조상에 VIEW 제한을 둔다 — 제한이 없으면 판정이 조기 종료해 비용 차이가 드러나지 않는다.
        restrictions.save(PageRestriction.of(root.getId(), PageRestriction.Type.VIEW,
                PageRestriction.PrincipalType.USER, USER, USER));
        return target;
    }

    private long queriesForGet(long pageId) throws Exception {
        Statistics stats = statistics();
        stats.clear();
        mvc.perform(get("/api/wiki/pages/" + pageId).with(asUser(USER, "Alice")))
                .andExpect(status().isOk());
        return stats.getPrepareStatementCount();
    }

    @Test
    void 페이지_조회_질의_수는_스페이스_크기와_무관하다() throws Exception {
        long smallTarget = buildSpace("small", 5);
        long largeTarget = buildSpace("large", 300);

        long small = queriesForGet(smallTarget);
        long large = queriesForGet(largeTarget);

        assertThat(small).isPositive();
        assertThat(large).isEqualTo(small);
    }

    /** 상속된 제한이 여전히 막는지 — 비용을 줄이면서 판정이 느슨해지면 최악이다. */
    @Test
    void 큰_스페이스에서도_조상_제한은_그대로_막는다() throws Exception {
        long target = buildSpace("guard", 300);
        long other = 2L;
        Space space = spaces.findAll().stream()
                .filter(s -> s.getKey().equals("guard")).findFirst().orElseThrow();
        perms.allow(other, space.getId(), WikiAction.VIEW);

        mvc.perform(get("/api/wiki/pages/" + target).with(asUser(other, "Bob")))
                .andExpect(status().isForbidden());
    }
}
