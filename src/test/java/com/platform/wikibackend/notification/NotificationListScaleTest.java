package com.platform.wikibackend.notification;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Notification;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.NotificationRepository;
import com.platform.wikibackend.repository.PageRepository;
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

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 알림함 비용이 쌓인 알림 수에 비례하지 않는다(감사 W-11).
 *
 * 안 읽은 알림 전량을 엔티티로, 그 페이지들을 본문까지 든 엔티티로 매번 읽었다 — 60초 폴링이 붙으면서
 * 탭 하나당 분당 1회씩 든다. 건수는 "볼 수 있는 것만"이라는 의미를 그대로 지킨다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
class NotificationListScaleTest {

    static final long ME = 1L;
    static final long ACTOR = 2L;

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired NotificationRepository notifications;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;
    MockMvc mvc;
    Space space;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        notifications.deleteAll();
        jdbc.update("delete from page_restriction");
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        space = spaces.save(Space.of("nscale", "알림", null, ME));
        perms.allow(ME, space.getId(), WikiAction.VIEW);
    }

    private void unread(int count) {
        for (int i = 0; i < count; i++) {
            Page page = pages.save(Page.of(space.getId(), null, "문서 " + i, "본문 ".repeat(200), ACTOR));
            notifications.save(Notification.of(ME, Notification.Type.PAGE_UPDATED, page.getId(), ACTOR));
        }
    }

    /** 알림함 한 번이 메모리로 올리는 엔티티 수 — 본문을 든 Page·안 읽은 알림 전량이 여기서 드러난다. */
    private long entitiesForList() throws Exception {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        mvc.perform(get("/api/wiki/notifications").with(asUser(ME, "Me"))).andExpect(status().isOk());
        return stats.getEntityLoadCount();
    }

    @Test
    void 알림함은_표시할_한_쪽만_엔티티로_읽는다() throws Exception {
        unread(80);

        // 표시용 한 쪽(30 + 다음 쪽 확인 1)만 엔티티다. 안 읽은 알림 전량(80)과 그 페이지(본문 포함 80)는
        // 프로젝션·집계로 처리한다(수정 전 160).
        assertThat(entitiesForList()).isLessThanOrEqualTo(31);
        mvc.perform(get("/api/wiki/notifications").with(asUser(ME, "Me")))
                .andExpect(jsonPath("$.unreadCount").value(80))
                .andExpect(jsonPath("$.items.length()").value(30));
    }

    /** 건수는 "지금 볼 수 있는 것만" — 제한된 문서의 알림은 세지 않는다. */
    @Test
    void 안_읽은_건수는_볼_수_있는_문서만_센다() throws Exception {
        unread(3);
        Page hidden = pages.save(Page.of(space.getId(), null, "비밀", "본문", ACTOR));
        notifications.save(Notification.of(ME, Notification.Type.PAGE_UPDATED, hidden.getId(), ACTOR));
        jdbc.update("insert into page_restriction (page_id, type, principal_type, principal_id, created_by, created_at)"
                + " values (?, 'VIEW', 'USER', ?, ?, current_timestamp)", hidden.getId(), ACTOR, ACTOR);

        mvc.perform(get("/api/wiki/notifications").with(asUser(ME, "Me")))
                .andExpect(jsonPath("$.unreadCount").value(3))
                .andExpect(jsonPath("$.items.length()").value(3));
    }

    // ── F-05: 전체 알림 화면 — 커서 쪽 넘김·종류 필터 ──

    @Test
    void 커서로_이전_알림을_끝까지_읽는다() throws Exception {
        unread(45);
        String first = mvc.perform(get("/api/wiki/notifications").param("limit", "20").with(asUser(ME, "Me")))
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.nextBefore").isNumber())
                .andReturn().getResponse().getContentAsString();
        long next = com.jayway.jsonpath.JsonPath.parse(first).read("$.nextBefore", Long.class);
        String second = mvc.perform(get("/api/wiki/notifications").param("limit", "20")
                        .param("before", String.valueOf(next)).with(asUser(ME, "Me")))
                .andExpect(jsonPath("$.items.length()").value(20))
                .andReturn().getResponse().getContentAsString();
        long next2 = com.jayway.jsonpath.JsonPath.parse(second).read("$.nextBefore", Long.class);
        mvc.perform(get("/api/wiki/notifications").param("limit", "20")
                        .param("before", String.valueOf(next2)).with(asUser(ME, "Me")))
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.nextBefore").doesNotExist());
        // 겹침 없음: 첫 쪽 마지막 id > 둘째 쪽 첫 id
        long lastOfFirst = com.jayway.jsonpath.JsonPath.parse(first).read("$.items[19].id", Long.class);
        long firstOfSecond = com.jayway.jsonpath.JsonPath.parse(second).read("$.items[0].id", Long.class);
        assertThat(firstOfSecond).isLessThan(lastOfFirst);
    }

    @Test
    void 종류_필터는_그_종류만_준다() throws Exception {
        unread(3);
        Page page = pages.save(Page.of(space.getId(), null, "멘션 문서", "본문", ACTOR));
        notifications.save(Notification.of(ME, Notification.Type.MENTIONED, page.getId(), ACTOR));

        mvc.perform(get("/api/wiki/notifications").param("type", "MENTIONED").with(asUser(ME, "Me")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].type").value("MENTIONED"))
                .andExpect(jsonPath("$.unreadCount").value(4)); // 배지는 필터와 무관한 전체 수
        mvc.perform(get("/api/wiki/notifications").param("type", "nope").with(asUser(ME, "Me")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 전체_읽음은_한_문장으로_처리된다() throws Exception {
        unread(40);
        mvc.perform(post("/api/wiki/notifications/read").with(asUser(ME, "Me"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is2xxSuccessful());

        mvc.perform(get("/api/wiki/notifications").with(asUser(ME, "Me")))
                .andExpect(jsonPath("$.unreadCount").value(0));
    }
}
