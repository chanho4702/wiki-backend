package com.platform.wikibackend.attachment;

import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.event.RecordingEventPublisher;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class AttachmentTest {

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired AttachmentRepository attachments;
    @Autowired FakePermissionClient perms;
    @Autowired RecordingEventPublisher events;
    MockMvc mvc;

    long pageId;
    static final long EDITOR = 1L;
    static final long VIEWER = 2L;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        attachments.deleteAll();
        pages.deleteAll();
        spaces.deleteAll();
        perms.reset();
        events.reset();
        Space s = spaces.save(Space.of("dev", "개발", null, EDITOR));
        perms.allow(EDITOR, s.getId(), WikiAction.VIEW);
        perms.allow(EDITOR, s.getId(), WikiAction.EDIT);
        perms.allow(VIEWER, s.getId(), WikiAction.VIEW);
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + s.getId() + ",\"parentId\":null,\"title\":\"t\",\"content\":\"c\"}"))
                .andReturn().getResponse().getContentAsString();
        pageId = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
    }

    private MockMultipartFile png() {
        return new MockMultipartFile("file", "스크린샷.png", "image/png", new byte[]{1, 2, 3, 4});
    }

    @Test
    void EDIT_사용자만_업로드할_수_있고_이벤트가_발행된다() throws Exception {
        mvc.perform(multipart("/api/wiki/pages/" + pageId + "/attachments").file(png())
                        .with(asUser(VIEWER, "Bob")))
                .andExpect(status().isForbidden());

        mvc.perform(multipart("/api/wiki/pages/" + pageId + "/attachments").file(png())
                        .with(asUser(EDITOR, "Alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value("스크린샷.png"));

        assertThat(events.events).anyMatch(e -> e.hasAttachmentAdded());
    }

    @Test
    void 다운로드는_원본_바이트와_attachment_헤더를_준다() throws Exception {
        String body = mvc.perform(multipart("/api/wiki/pages/" + pageId + "/attachments").file(png())
                        .with(asUser(EDITOR, "Alice")))
                .andReturn().getResponse().getContentAsString();
        long id = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);

        mvc.perform(get("/api/wiki/attachments/" + id).with(asUser(VIEWER, "Bob")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(content().bytes(new byte[]{1, 2, 3, 4}));
    }

    @Test
    void 삭제하면_메타와_파일이_사라진다() throws Exception {
        String body = mvc.perform(multipart("/api/wiki/pages/" + pageId + "/attachments").file(png())
                        .with(asUser(EDITOR, "Alice")))
                .andReturn().getResponse().getContentAsString();
        long id = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);

        mvc.perform(delete("/api/wiki/attachments/" + id).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/wiki/attachments/" + id).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isNotFound());
        assertThat(attachments.findByPageId(pageId)).isEmpty();
    }

    @Test
    void 페이지를_삭제하면_첨부_행도_함께_정리된다() throws Exception {
        mvc.perform(multipart("/api/wiki/pages/" + pageId + "/attachments").file(png())
                .with(asUser(EDITOR, "Alice"))).andExpect(status().isCreated());

        mvc.perform(delete("/api/wiki/pages/" + pageId).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isNoContent());

        assertThat(attachments.findByPageId(pageId)).isEmpty();
    }

    /** 첨부가 사라졌는데 색인에 남으면 검색 결과가 404로 이어진다(proto v0.3.0). */
    @Test
    void 첨부_단건_삭제는_AttachmentDeleted_이벤트를_낸다() throws Exception {
        String body = mvc.perform(multipart("/api/wiki/pages/" + pageId + "/attachments").file(png())
                        .with(asUser(EDITOR, "Alice")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
        events.reset();

        mvc.perform(delete("/api/wiki/attachments/" + id).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isNoContent());

        assertThat(events.events).anyMatch(e -> e.hasAttachmentDeleted()
                && e.getAttachmentDeleted().getAttachmentId() == id
                && e.getAttachmentDeleted().getPageId() == pageId);
    }

    /**
     * 페이지·스페이스 삭제로 딸려 사라지는 첨부는 개별 이벤트를 내지 않는다 —
     * 상위 PageDeleted로 소비자가 함께 정리한다(큰 트리 삭제 시 스트림 폭주 방지).
     */
    @Test
    void 페이지_삭제로_딸려간_첨부는_개별_이벤트를_내지_않는다() throws Exception {
        mvc.perform(multipart("/api/wiki/pages/" + pageId + "/attachments").file(png())
                .with(asUser(EDITOR, "Alice"))).andExpect(status().isCreated());
        events.reset();

        mvc.perform(delete("/api/wiki/pages/" + pageId).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isNoContent());

        assertThat(events.events).anyMatch(e -> e.hasPageDeleted());
        assertThat(events.events).noneMatch(e -> e.hasAttachmentDeleted());
    }
}
