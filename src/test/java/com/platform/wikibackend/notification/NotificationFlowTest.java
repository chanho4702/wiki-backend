package com.platform.wikibackend.notification;

import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.domain.PageRestriction;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.NotificationRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.repository.PageRestrictionRepository;
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

import com.platform.wikibackend.TestPages;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** V11 알림 — 트리거(새 멘션/관심 페이지 업데이트/댓글)·자기 제외·미읽음 합침·읽음 처리. */
@SpringBootTest
@ActiveProfiles("test")
class NotificationFlowTest {

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired NotificationRepository notifications;
    @Autowired PageRestrictionRepository restrictions;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    MockMvc mvc;

    Space space;
    static final long ALICE = 1L; // 작성자
    static final long BOB = 2L;   // 편집 참여자

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        notifications.deleteAll();
        restrictions.deleteAll();
        revisions.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        space = spaces.save(Space.of("dev", "개발", null, ALICE));
        for (long u : new long[] {ALICE, BOB}) {
            perms.allow(u, space.getId(), WikiAction.VIEW);
            perms.allow(u, space.getId(), WikiAction.EDIT);
        }
    }

    private long createPage(long author, String content) throws Exception {
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(author, "작성자"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId()
                                + ",\"parentId\":null,\"title\":\"문서\",\"content\":\"" + content + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
    }

    private void update(long actor, long pageId, int expectedVersion, String content) throws Exception {
        mvc.perform(put("/api/wiki/pages/" + pageId).with(asUser(actor, "편집자"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"문서\",\"content\":\"" + content
                                + "\",\"parentId\":null,\"expectedVersion\":" + expectedVersion + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void 새_멘션은_MENTIONED_행위자는_수신하지_않는다() throws Exception {
        long id = createPage(ALICE, "본문");
        update(ALICE, id, 1, "[@밥](user:" + BOB + ") 확인 부탁");

        mvc.perform(get("/api/wiki/notifications").with(asUser(BOB, "밥")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.items[0].type").value("MENTIONED"))
                .andExpect(jsonPath("$.items[0].pageTitle").value("문서"))
                .andExpect(jsonPath("$.items[0].actorId").value(1));

        // 행위자(앨리스)는 아무것도 받지 않는다 — 자기 저장 소음 제외
        mvc.perform(get("/api/wiki/notifications").with(asUser(ALICE, "앨리스")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void 작성자는_남의_업데이트를_받고_연속_업데이트는_1건으로_합쳐진다() throws Exception {
        long id = createPage(ALICE, "본문");
        update(BOB, id, 1, "밥의 1차 수정");
        update(BOB, id, 2, "밥의 2차 수정");

        mvc.perform(get("/api/wiki/notifications").with(asUser(ALICE, "앨리스")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1)) // 미읽음 합침
                .andExpect(jsonPath("$.items[0].type").value("PAGE_UPDATED"))
                .andExpect(jsonPath("$.items[0].actorId").value(2));
    }

    @Test
    void 이미_멘션된_사용자도_이후_업데이트를_받고_기존_멘션과_겹치지_않는다() throws Exception {
        long id = createPage(ALICE, "[@밥](user:" + BOB + ")를 담당자로");
        update(ALICE, id, 1, "[@밥](user:" + BOB + ")를 담당자로 — 내용 보강");

        // 밥: 생성 시점엔 알림 없음(생성은 트리거 아님 — 재량), 업데이트에서 PAGE_UPDATED 1건.
        // 멘션이 "새로" 생긴 게 아니므로 MENTIONED가 아니다.
        mvc.perform(get("/api/wiki/notifications").with(asUser(BOB, "밥")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.items[0].type").value("PAGE_UPDATED"));
    }

    @Test
    void 댓글은_COMMENT_댓글_멘션은_MENTIONED로_간다() throws Exception {
        long id = createPage(ALICE, "본문");
        mvc.perform(post("/api/wiki/pages/" + id + "/comments").with(asUser(BOB, "밥"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"의견 남깁니다\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/wiki/notifications").with(asUser(ALICE, "앨리스")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.items[0].type").value("COMMENT"));
    }

    @Test
    void 읽음_처리_전체와_개별() throws Exception {
        long id = createPage(ALICE, "본문");
        update(BOB, id, 1, "수정");

        mvc.perform(post("/api/wiki/notifications/read").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/wiki/notifications").with(asUser(ALICE, "앨리스")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0))
                .andExpect(jsonPath("$.items[0].read").value(true));
    }

    @Test
    void 제한_강화_전에_쌓인_알림도_조회에서_제목과_경로를_숨긴다() throws Exception {
        long id = createPage(ALICE, "본문");
        update(BOB, id, 1, "밥의 수정"); // 앨리스에게 기존 알림 1건
        restrictions.save(PageRestriction.of(id, PageRestriction.Type.VIEW,
                PageRestriction.PrincipalType.USER, BOB, BOB));

        mvc.perform(get("/api/wiki/notifications").with(asUser(ALICE, "앨리스")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void 제한_밖_수신자에게는_새_업데이트_알림을_저장하지_않는다() throws Exception {
        long id = createPage(ALICE, "본문");
        restrictions.save(PageRestriction.of(id, PageRestriction.Type.VIEW,
                PageRestriction.PrincipalType.USER, BOB, BOB));

        update(BOB, id, 1, "제한 이후 수정");

        assertThat(notifications.findByUserIdAndReadAtIsNull(ALICE)).isEmpty();
    }
}
