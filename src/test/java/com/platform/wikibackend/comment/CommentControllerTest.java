package com.platform.wikibackend.comment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageCommentRepository;
import com.platform.wikibackend.repository.PageRepository;
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
 * 페이지 댓글/답글 계약 — 목업(wikiMock) 규칙을 서버에서 고정한다:
 * 1단 답글, 작성자만 수정/삭제(+ADMIN moderation), 최상위 삭제 시 답글 연쇄, 무변경 no-op.
 */
@SpringBootTest
@ActiveProfiles("test")
class CommentControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long AUTHOR = 1L;
    private static final long OTHER = 2L;
    private static final long ADMIN = 3L;

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageCommentRepository comments;
    @Autowired FakePermissionClient perms;

    private Long pageId;
    private Long spaceId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        comments.deleteAllInBatch();
        pages.deleteAllInBatch();
        spaces.deleteAllInBatch();
        perms.reset();
        Space space = spaces.save(Space.of("cmt" + (System.nanoTime() % 100000), "댓글", null, AUTHOR));
        spaceId = space.getId();
        pageId = pages.save(Page.of(spaceId, null, "본문", "", AUTHOR)).getId();
        perms.allow(AUTHOR, spaceId, WikiAction.VIEW);
        perms.allow(OTHER, spaceId, WikiAction.VIEW);
        perms.allow(ADMIN, spaceId, WikiAction.VIEW);
        perms.allow(ADMIN, spaceId, WikiAction.ADMIN);
    }

    @Test
    void 댓글과_답글을_달고_재조회하면_같은_thread가_보인다() throws Exception {
        long parentId = createComment(AUTHOR, "Alice", "첫 댓글", null);

        mvc.perform(post("/api/wiki/pages/{id}/comments", pageId).with(asUser(OTHER, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"  답글  \",\"parentId\":" + parentId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentId").value(parentId))
                .andExpect(jsonPath("$.body").value("답글"))
                .andExpect(jsonPath("$.authorId").value(OTHER))
                .andExpect(jsonPath("$.authorName").value("Bob"))
                .andExpect(jsonPath("$.updatedAt").doesNotExist());

        // 다른 사용자의 재조회에서도 동일 thread — localStorage가 아니라 서버 영속이다.
        mvc.perform(get("/api/wiki/pages/{id}/comments", pageId).with(asUser(ADMIN, "Carol")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].body").value("첫 댓글"))
                .andExpect(jsonPath("$[0].parentId").doesNotExist())
                .andExpect(jsonPath("$[1].parentId").value(parentId));
    }

    @Test
    void 답글에는_답글을_달_수_없다() throws Exception {
        long parentId = createComment(AUTHOR, "Alice", "최상위", null);
        long replyId = createComment(OTHER, "Bob", "답글", parentId);

        mvc.perform(post("/api/wiki/pages/{id}/comments", pageId).with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"깊은 답글\",\"parentId\":" + replyId + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("답글에는 답글을 달 수 없습니다"));
    }

    @Test
    void 다른_페이지의_댓글을_부모로_지정할_수_없다() throws Exception {
        long otherPage = pages.save(Page.of(spaceId, null, "다른 페이지", "", AUTHOR)).getId();
        long parentId = createComment(AUTHOR, "Alice", "원 페이지 댓글", null);

        mvc.perform(post("/api/wiki/pages/{id}/comments", otherPage).with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"엉뚱한 답글\",\"parentId\":" + parentId + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("부모 코멘트가 같은 페이지에 없습니다"));
    }

    @Test
    void 작성자만_수정할_수_있고_무변경은_수정_표시를_남기지_않는다() throws Exception {
        long id = createComment(AUTHOR, "Alice", "원문", null);

        mvc.perform(put("/api/wiki/comments/{id}", id).with(asUser(OTHER, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"탈취 시도\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("본인의 코멘트만 수정할 수 있습니다"));

        // 같은 본문 재저장은 no-op — "(수정됨)"이 붙지 않아야 한다.
        mvc.perform(put("/api/wiki/comments/{id}", id).with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\" 원문 \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedAt").doesNotExist());

        mvc.perform(put("/api/wiki/comments/{id}", id).with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"고친 본문\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("고친 본문"))
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void 최상위_댓글을_지우면_답글도_함께_사라진다() throws Exception {
        long parentId = createComment(AUTHOR, "Alice", "최상위", null);
        createComment(OTHER, "Bob", "답글", parentId);

        mvc.perform(delete("/api/wiki/comments/{id}", parentId).with(asUser(AUTHOR, "Alice")))
                .andExpect(status().isNoContent());

        assertThat(comments.count()).isZero();
    }

    @Test
    void 삭제는_작성자_또는_스페이스_ADMIN만_할_수_있다() throws Exception {
        long id = createComment(AUTHOR, "Alice", "지울 댓글", null);

        mvc.perform(delete("/api/wiki/comments/{id}", id).with(asUser(OTHER, "Bob")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("본인의 코멘트만 삭제할 수 있습니다"));

        // ADMIN은 moderation으로 남의 댓글도 지운다.
        mvc.perform(delete("/api/wiki/comments/{id}", id).with(asUser(ADMIN, "Carol")))
                .andExpect(status().isNoContent());
    }

    @Test
    void VIEW_권한이_없으면_읽지도_쓰지도_못한다() throws Exception {
        long stranger = 99L;
        mvc.perform(get("/api/wiki/pages/{id}/comments", pageId).with(asUser(stranger, "Mallory")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/wiki/pages/{id}/comments", pageId).with(asUser(stranger, "Mallory"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"몰래 댓글\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 빈_본문과_없는_페이지는_거부된다() throws Exception {
        mvc.perform(post("/api/wiki/pages/{id}/comments", pageId).with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/wiki/pages/{id}/comments", pageId + 999).with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"유령 페이지\"}"))
                .andExpect(status().isNotFound());
    }

    private long createComment(long userId, String name, String body, Long parentId) throws Exception {
        String parent = parentId == null ? "null" : String.valueOf(parentId);
        String response = mvc.perform(post("/api/wiki/pages/{id}/comments", pageId)
                        .with(asUser(userId, name))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + body + "\",\"parentId\":" + parent + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("id").asLong();
    }
}
