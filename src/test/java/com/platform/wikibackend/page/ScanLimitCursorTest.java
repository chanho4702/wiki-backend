package com.platform.wikibackend.page;

import com.jayway.jsonpath.JsonPath;
import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Notification;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRestriction;
import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.domain.PageType;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.NotificationRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRestrictionRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스캔 상한에 걸려 멈춘 쪽 목록이 "끝"으로 보이지 않는다(Codex 리뷰 2026-10-18).
 *
 * 볼 수 없는 행이 상한보다 많이 몰려 있으면, 보이는 항목 기준 커서는 숨은 구간을 매번 다시 훑고 처음 구간이
 * 전부 숨은 행이면 빈 목록 + 커서 없음 = 끝처럼 보였다. 마지막으로 스캔한 행을 커서로 준다.
 * 상한을 낮춰(설정) 작은 데이터로 재현한다.
 */
@SpringBootTest(properties = {
        "platform.wiki.notifications.list-scan-limit=10",
        "platform.wiki.blog.scan-limit=4",
})
@ActiveProfiles("test")
class ScanLimitCursorTest {

    static final long ME = 1L;
    static final long OTHER = 2L;

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRestrictionRepository restrictions;
    @Autowired NotificationRepository notifications;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    MockMvc mvc;
    Space space;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        notifications.deleteAll();
        restrictions.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        space = spaces.save(Space.of("scan" + (System.nanoTime() % 100000), "스캔", null, ME));
        perms.allow(ME, space.getId(), WikiAction.VIEW);
    }

    @Test
    void 알림_숨은_구간이_스캔_상한보다_길어도_커서로_이어_읽어_보이는_알림에_닿는다() throws Exception {
        List<Long> visible = new ArrayList<>();
        for (int i = 0; i < 3; i++) visible.add(notify(false));   // 오래된 보이는 알림 3건
        for (int i = 0; i < 25; i++) notify(true);                // 그보다 새 숨은 알림 25건

        String first = mvc.perform(get("/api/wiki/notifications").param("limit", "5").with(asUser(ME, "Me")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(first, "$.items")).isEmpty();
        assertThat(JsonPath.<Object>read(first, "$.nextBefore")).as("빈 쪽이어도 끝이 아니다").isNotNull();

        List<Long> seen = new ArrayList<>();
        Object cursor = JsonPath.read(first, "$.nextBefore");
        for (int guard = 0; cursor != null && guard < 10; guard++) {
            String body = mvc.perform(get("/api/wiki/notifications").param("limit", "5")
                            .param("before", String.valueOf(cursor)).with(asUser(ME, "Me")))
                    .andReturn().getResponse().getContentAsString();
            for (Object id : JsonPath.<List<Object>>read(body, "$.items[*].id")) seen.add(((Number) id).longValue());
            cursor = JsonPath.read(body, "$.nextBefore");
        }
        assertThat(cursor).as("끝에 닿으면 커서가 없다").isNull();
        assertThat(seen).containsExactlyInAnyOrderElementsOf(visible);
    }

    @Test
    void 블로그_숨은_글_구간에서_멈춰도_헤더_커서로_이어_읽는다() throws Exception {
        perms.allow(OTHER, space.getId(), WikiAction.VIEW);
        List<Long> visible = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            visible.add(pages.save(Page.of(space.getId(), null, "보이는 글 " + i, "본문", OTHER,
                    PageType.BLOG, PageStatus.PUBLISHED)).getId());
        }
        for (int i = 0; i < 9; i++) {   // 남의 초안 — 나에게는 숨은 글
            pages.save(Page.of(space.getId(), null, "남의 초안 " + i, "본문", OTHER, PageType.BLOG, PageStatus.DRAFT));
        }

        MockHttpServletResponse first = blog(null);
        assertThat(JsonPath.<List<Object>>read(first.getContentAsString(), "$")).isEmpty();
        assertThat(first.getHeader(BlogController.NEXT_BEFORE)).as("빈 배열이어도 끝이 아니다").isNotNull();

        List<Long> seen = new ArrayList<>();
        String cursor = first.getHeader(BlogController.NEXT_BEFORE);
        for (int guard = 0; cursor != null && guard < 10; guard++) {
            MockHttpServletResponse res = blog(cursor);
            for (Object id : JsonPath.<List<Object>>read(res.getContentAsString(), "$[*].id")) {
                seen.add(((Number) id).longValue());
            }
            cursor = res.getHeader(BlogController.NEXT_BEFORE);
        }
        assertThat(cursor).isNull();
        assertThat(seen).containsExactlyInAnyOrderElementsOf(visible);
    }

    private MockHttpServletResponse blog(String before) throws Exception {
        var request = get("/api/wiki/spaces/{id}/blog", space.getId()).param("limit", "1").with(asUser(ME, "Me"));
        if (before != null) request.param("before", before);
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
    }

    private long notify(boolean hidden) {
        Page page = pages.save(Page.of(space.getId(), null, hidden ? "숨은 문서" : "보이는 문서", "본문", OTHER));
        if (hidden) {
            restrictions.save(PageRestriction.of(page.getId(), PageRestriction.Type.VIEW,
                    PageRestriction.PrincipalType.USER, OTHER, OTHER));
        }
        return notifications.save(Notification.of(ME, Notification.Type.PAGE_UPDATED, page.getId(), OTHER)).getId();
    }
}
