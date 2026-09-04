package com.platform.wikibackend.watch;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Notification;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.NotificationRepository;
import com.platform.wikibackend.repository.PageCommentRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.repository.PageWatchRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import com.platform.wikibackend.repository.SpaceWatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 스페이스 구독(W27-4) — 토글, 알림 합집합, 새 문서 게시 알림. */
@SpringBootTest
@ActiveProfiles("test")
class SpaceWatchTest {

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired PageCommentRepository comments;
    @Autowired PageWatchRepository pageWatches;
    @Autowired SpaceWatchRepository spaceWatches;
    @Autowired NotificationRepository notifications;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    MockMvc mvc;

    Space space;
    static final long AUTHOR = 1L;
    static final long SUBSCRIBER = 2L;
    static final long OUTSIDER = 3L;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        notifications.deleteAll();
        comments.deleteAll();
        pageWatches.deleteAll();
        spaceWatches.deleteAll();
        revisions.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        space = spaces.save(Space.of("dev", "개발", null, AUTHOR));
        for (long user : new long[]{AUTHOR, SUBSCRIBER}) {
            perms.allow(user, space.getId(), WikiAction.VIEW);
            perms.allow(user, space.getId(), WikiAction.EDIT);
        }
    }

    private long createPage(long user, String title, String content) throws Exception {
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(user, "U" + user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId() + ",\"parentId\":null,\"title\":\"" + title
                                + "\",\"content\":\"" + content + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
    }

    private void subscribeSpace(long user) throws Exception {
        mvc.perform(put("/api/wiki/spaces/" + space.getId() + "/watch").with(asUser(user, "U" + user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watching").value(true));
    }

    @Test
    void 스페이스_구독은_켜고_끌_수_있고_자동_구독은_없다() throws Exception {
        // 스페이스를 만든 사람도 자동으로 구독되지 않는다 — 그런 관심의 사건이 없다
        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/watch").with(asUser(AUTHOR, "Alice")))
                .andExpect(jsonPath("$.watching").value(false));

        subscribeSpace(AUTHOR);
        assertThat(spaceWatches.existsBySpaceIdAndUserId(space.getId(), AUTHOR)).isTrue();

        // 두 번 켜도 같은 상태 — 멱등
        subscribeSpace(AUTHOR);
        assertThat(spaceWatches.findWatcherIds(space.getId())).containsExactly(AUTHOR);

        mvc.perform(delete("/api/wiki/spaces/" + space.getId() + "/watch").with(asUser(AUTHOR, "Alice")))
                .andExpect(jsonPath("$.watching").value(false));
        assertThat(spaceWatches.existsBySpaceIdAndUserId(space.getId(), AUTHOR)).isFalse();
    }

    @Test
    void 볼_수_없는_스페이스는_구독할_수_없다() throws Exception {
        mvc.perform(put("/api/wiki/spaces/" + space.getId() + "/watch").with(asUser(OUTSIDER, "Eve")))
                .andExpect(status().isForbidden());
        assertThat(spaceWatches.existsBySpaceIdAndUserId(space.getId(), OUTSIDER)).isFalse();
    }

    @Test
    void 스페이스_구독자는_그_안의_문서_수정_알림을_받는다() throws Exception {
        long pageId = createPage(AUTHOR, "보고서", "처음");
        // 페이지 구독은 하지 않았다 — 알림은 스페이스 구독만으로 온다
        assertThat(pageWatches.existsByPageIdAndUserId(pageId, SUBSCRIBER)).isFalse();
        subscribeSpace(SUBSCRIBER);

        mvc.perform(put("/api/wiki/pages/" + pageId).with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"보고서\",\"content\":\"고침\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());

        List<Notification> received = notifications.findByUserIdAndReadAtIsNull(SUBSCRIBER);
        assertThat(received).hasSize(1);
        assertThat(received.getFirst().getType()).isEqualTo(Notification.Type.PAGE_UPDATED);
    }

    /** 두 원장에 다 있어도 한 번만 — 합집합이지 두 번 보내는 것이 아니다. */
    @Test
    void 페이지와_스페이스를_모두_구독해도_알림은_한_건이다() throws Exception {
        long pageId = createPage(AUTHOR, "보고서", "처음");
        subscribeSpace(SUBSCRIBER);
        mvc.perform(post("/api/wiki/pages/" + pageId + "/watch").with(asUser(SUBSCRIBER, "Bob")))
                .andExpect(status().isOk());

        mvc.perform(put("/api/wiki/pages/" + pageId).with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"보고서\",\"content\":\"고침\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());

        assertThat(notifications.findByUserIdAndReadAtIsNull(SUBSCRIBER)).hasSize(1);
    }

    @Test
    void 새_문서를_만들면_스페이스_구독자에게_게시_알림이_간다() throws Exception {
        subscribeSpace(SUBSCRIBER);

        createPage(AUTHOR, "새 문서", "내용");

        List<Notification> received = notifications.findByUserIdAndReadAtIsNull(SUBSCRIBER);
        assertThat(received).hasSize(1);
        assertThat(received.getFirst().getType()).isEqualTo(Notification.Type.PAGE_PUBLISHED);
        // 게시한 본인에게는 오지 않는다
        assertThat(notifications.findByUserIdAndReadAtIsNull(AUTHOR)).isEmpty();
    }

    @Test
    void 초안은_게시할_때_알린다() throws Exception {
        subscribeSpace(SUBSCRIBER);
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId()
                                + ",\"parentId\":null,\"title\":\"초안\",\"content\":\"쓰는 중\",\"status\":\"DRAFT\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long pageId = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
        assertThat(notifications.findByUserIdAndReadAtIsNull(SUBSCRIBER)).isEmpty(); // 초안은 아직 아니다

        mvc.perform(post("/api/wiki/pages/" + pageId + "/publish").with(asUser(AUTHOR, "Alice")))
                .andExpect(status().isOk());

        List<Notification> received = notifications.findByUserIdAndReadAtIsNull(SUBSCRIBER);
        assertThat(received).hasSize(1);
        assertThat(received.getFirst().getType()).isEqualTo(Notification.Type.PAGE_PUBLISHED);
    }

    /** 제한된 문서(V12)는 스페이스 구독으로도 새어나가지 않는다 — 제목이 알림함에 뜨면 그 자체가 누출이다. */
    @Test
    void 제한된_문서는_볼_수_없는_스페이스_구독자에게_알리지_않는다() throws Exception {
        long pageId = createPage(AUTHOR, "비밀", "내용");
        subscribeSpace(SUBSCRIBER);
        mvc.perform(put("/api/wiki/pages/" + pageId + "/restrictions").with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"view\":[{\"type\":\"USER\",\"id\":" + AUTHOR + "}],\"edit\":[]}"))
                .andExpect(status().isOk());

        mvc.perform(put("/api/wiki/pages/" + pageId).with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"비밀\",\"content\":\"고침\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());

        assertThat(notifications.findByUserIdAndReadAtIsNull(SUBSCRIBER)).isEmpty();
    }
}
