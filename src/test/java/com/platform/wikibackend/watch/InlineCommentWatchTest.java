package com.platform.wikibackend.watch;

import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.NotificationRepository;
import com.platform.wikibackend.repository.PageCommentRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.repository.PageWatchRepository;
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

/** 인라인 댓글과 구독(W21-4). */
@SpringBootTest
@ActiveProfiles("test")
class InlineCommentWatchTest {

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired PageCommentRepository comments;
    @Autowired PageWatchRepository watches;
    @Autowired NotificationRepository notifications;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    MockMvc mvc;

    Space space;
    long pageId;
    static final long AUTHOR = 1L;
    static final long READER = 2L;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        notifications.deleteAll();
        comments.deleteAll();
        watches.deleteAll();
        revisions.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        space = spaces.save(Space.of("dev", "개발", null, AUTHOR));
        for (long user : new long[]{AUTHOR, READER}) {
            perms.allow(user, space.getId(), WikiAction.VIEW);
            perms.allow(user, space.getId(), WikiAction.EDIT);
        }
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId()
                                + ",\"parentId\":null,\"title\":\"보고서\",\"content\":\"배포는 금요일에 한다. 배포는 금요일에 한다.\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        pageId = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
    }

    private long inlineComment(long user, String quote, Integer occurrence) throws Exception {
        String body = mvc.perform(post("/api/wiki/pages/" + pageId + "/comments").with(asUser(user, "U"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"이거 맞나요?\",\"anchorQuote\":\"" + quote + "\""
                                + (occurrence == null ? "" : ",\"anchorOccurrence\":" + occurrence) + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
    }

    @Test
    void 본문_구간에_댓글을_달면_앵커가_함께_저장된다() throws Exception {
        long id = inlineComment(READER, "금요일", 1);

        mvc.perform(get("/api/wiki/pages/" + pageId + "/comments").with(asUser(AUTHOR, "Alice")))
                .andExpect(jsonPath("$[0].id").value((int) id))
                .andExpect(jsonPath("$[0].anchorType").value("inline"))
                .andExpect(jsonPath("$[0].anchorQuote").value("금요일"))
                .andExpect(jsonPath("$[0].anchorOccurrence").value(1))
                .andExpect(jsonPath("$[0].resolvedAt").doesNotExist());
    }

    /**
     * 앵커는 렌더된 본문 기준이라 서버가 마크다운 원문과 대조하지 않는다 —
     * 서식을 가로지르는 선택은 원문에 그대로 없어서 정당한 선택까지 거부하게 된다.
     * 서버가 막는 것은 형식적으로 불가능한 값뿐이다.
     */
    @Test
    void 음수_위치는_거부하고_원문에_없는_인용은_그대로_보관한다() throws Exception {
        mvc.perform(post("/api/wiki/pages/" + pageId + "/comments").with(asUser(READER, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"질문\",\"anchorQuote\":\"금요일\",\"anchorOccurrence\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("본문 구간 위치가 올바르지 않습니다"));

        // 서식을 가로지른 선택(원문에는 `**금요일**`) — 거부하지 않는다
        mvc.perform(post("/api/wiki/pages/" + pageId + "/comments").with(asUser(READER, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"질문\",\"anchorQuote\":\"배포는 금요일에 한다\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anchorType").value("inline"));
    }

    @Test
    void 답글에는_본문_구간을_붙일_수_없다() throws Exception {
        long root = inlineComment(READER, "금요일", 0);

        mvc.perform(post("/api/wiki/pages/" + pageId + "/comments").with(asUser(READER, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"답\",\"parentId\":" + root + ",\"anchorQuote\":\"금요일\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("답글에는 본문 구간을 붙일 수 없습니다"));
    }

    @Test
    void 인라인_스레드는_해결하고_다시_열_수_있다() throws Exception {
        long id = inlineComment(READER, "금요일", 0);

        mvc.perform(put("/api/wiki/comments/" + id + "/resolved").with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resolved\":true}"))
                .andExpect(jsonPath("$.resolvedAt").exists());

        mvc.perform(put("/api/wiki/comments/" + id + "/resolved").with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resolved\":false}"))
                .andExpect(jsonPath("$.resolvedAt").doesNotExist());
    }

    @Test
    void 페이지_댓글은_해결_대상이_아니다() throws Exception {
        String body = mvc.perform(post("/api/wiki/pages/" + pageId + "/comments").with(asUser(READER, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"일반 댓글\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);

        mvc.perform(put("/api/wiki/comments/" + id + "/resolved").with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resolved\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("인라인 댓글만 해결할 수 있습니다"));
    }

    @Test
    void 페이지를_만들면_자동_구독되고_해제할_수_있다() throws Exception {
        mvc.perform(get("/api/wiki/pages/" + pageId + "/watch").with(asUser(AUTHOR, "Alice")))
                .andExpect(jsonPath("$.watching").value(true));

        mvc.perform(delete("/api/wiki/pages/" + pageId + "/watch").with(asUser(AUTHOR, "Alice")))
                .andExpect(jsonPath("$.watching").value(false));
        assertThat(watches.existsByPageIdAndUserId(pageId, AUTHOR)).isFalse();

        mvc.perform(post("/api/wiki/pages/" + pageId + "/watch").with(asUser(AUTHOR, "Alice")))
                .andExpect(jsonPath("$.watching").value(true));
    }

    @Test
    void 구독자는_수정_알림을_받고_해제하면_받지_않는다() throws Exception {
        mvc.perform(post("/api/wiki/pages/" + pageId + "/watch").with(asUser(READER, "Bob")))
                .andExpect(status().isOk());

        mvc.perform(put("/api/wiki/pages/" + pageId).with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"보고서\",\"content\":\"고침 1\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        assertThat(notifications.findByUserIdAndReadAtIsNull(READER)).hasSize(1);

        notifications.deleteAll();
        mvc.perform(delete("/api/wiki/pages/" + pageId + "/watch").with(asUser(READER, "Bob")))
                .andExpect(status().isOk());
        mvc.perform(put("/api/wiki/pages/" + pageId).with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"보고서\",\"content\":\"고침 2\",\"expectedVersion\":2}"))
                .andExpect(status().isOk());

        assertThat(notifications.findByUserIdAndReadAtIsNull(READER)).isEmpty();
    }

    @Test
    void 댓글을_달면_그_문서를_자동으로_구독한다() throws Exception {
        mvc.perform(post("/api/wiki/pages/" + pageId + "/comments").with(asUser(READER, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"질문 있습니다\"}"))
                .andExpect(status().isCreated());

        assertThat(watches.existsByPageIdAndUserId(pageId, READER)).isTrue();
    }
}
