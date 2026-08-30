package com.platform.wikibackend.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Attachment;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.repository.AttachmentVersionRepository;
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

import java.nio.charset.StandardCharsets;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 첨부 버전(W23).
 *
 * 같은 이름의 파일을 다시 올리면 예전에는 새 행이 생겨 **id가 바뀌었다**. 본문의 인라인 참조는
 * id로 걸려 있어서, 이미지를 고쳐 올려도 문서에는 옛 파일이 계속 보였다. 그 id 유지가 이
 * 기능의 요점이라 테스트도 거기에 건다.
 */
@SpringBootTest
@ActiveProfiles("test")
class AttachmentVersionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long EDITOR = 1L;

    // 1x1 PNG — detect()가 실제로 image/png로 읽는 바이트여야 한다(확장자는 보지 않는다).
    private static final byte[] PNG_V1 = pngBytes((byte) 0x01);
    private static final byte[] PNG_V2 = pngBytes((byte) 0x02);

    @Autowired WebApplicationContext context;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired AttachmentRepository attachments;
    @Autowired AttachmentVersionRepository versions;
    @Autowired FakePermissionClient perms;

    MockMvc mvc;
    Page page;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        versions.deleteAllInBatch();
        attachments.deleteAllInBatch();
        TestPages.deleteAll(jdbc);
        spaces.deleteAllInBatch();
        perms.reset();

        Space space = spaces.save(Space.of("av" + (System.nanoTime() % 100000), "첨부", null, EDITOR));
        perms.allow(EDITOR, space.getId(), WikiAction.VIEW);
        perms.allow(EDITOR, space.getId(), WikiAction.EDIT);
        page = pages.save(Page.of(space.getId(), null, "문서", "본문", EDITOR));
    }

    /** id가 유지되는 것이 요점이다 — 본문의 인라인 참조가 그대로 새 파일을 가리켜야 한다. */
    @Test
    void 같은_이름_재업로드는_같은_id의_새_버전이_된다() throws Exception {
        long first = upload("diagram.png", PNG_V1);

        long second = upload("diagram.png", PNG_V2);

        assertThat(second).isEqualTo(first);
        assertThat(attachments.findById(first).orElseThrow().getVersion()).isEqualTo(2);
        assertThat(attachments.findByPageId(page.getId())).hasSize(1);
    }

    @Test
    void 지난_버전이_이력에_남고_내려받을_수_있다() throws Exception {
        long id = upload("diagram.png", PNG_V1);
        upload("diagram.png", PNG_V2);

        mvc.perform(get("/api/wiki/attachments/{id}/versions", id).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].version").value(1));

        byte[] old = mvc.perform(get("/api/wiki/attachments/{id}/versions/{v}", id, 1)
                        .with(asUser(EDITOR, "Alice")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(old).isEqualTo(PNG_V1);
    }

    /** 현재는 새 파일이어야 한다 — 이력만 쌓이고 현재가 안 바뀌면 갈아끼운 의미가 없다. */
    @Test
    void 현재_내용은_새로_올린_파일이다() throws Exception {
        long id = upload("diagram.png", PNG_V1);
        upload("diagram.png", PNG_V2);

        byte[] current = mvc.perform(get("/api/wiki/attachments/{id}", id).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(current).isEqualTo(PNG_V2);
    }

    @Test
    void 되돌리면_옛_내용이_현재가_되고_되돌린_것도_버전으로_쌓인다() throws Exception {
        long id = upload("diagram.png", PNG_V1);
        upload("diagram.png", PNG_V2);

        mvc.perform(post("/api/wiki/attachments/{id}/versions/{v}/restore", id, 1)
                        .with(asUser(EDITOR, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        byte[] current = mvc.perform(get("/api/wiki/attachments/{id}", id).with(asUser(EDITOR, "Alice")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(current).isEqualTo(PNG_V1);
        // v1(원본) + v2(되돌리기 직전) = 2건. 되돌린 사실이 이력에 남는다.
        assertThat(versions.findByAttachmentIdOrderByVersionDesc(id)).hasSize(2);
    }

    @Test
    void 다른_이름은_별개_첨부다() throws Exception {
        long first = upload("diagram.png", PNG_V1);

        long second = upload("other.png", PNG_V2);

        assertThat(second).isNotEqualTo(first);
        assertThat(attachments.findByPageId(page.getId())).hasSize(2);
    }

    /** 첨부를 지우면 지난 버전 행도 함께 사라져야 한다(파일도 함께 치운다). */
    @Test
    void 첨부를_지우면_지난_버전도_사라진다() throws Exception {
        long id = upload("diagram.png", PNG_V1);
        upload("diagram.png", PNG_V2);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/wiki/attachments/{id}", id).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isNoContent());

        assertThat(versions.findByAttachmentIdOrderByVersionDesc(id)).isEmpty();
    }

    /**
     * PDF는 인라인으로 연다 — 브라우저 내장 뷰어가 자체 샌드박스에서 열고 DOM에 닿지 못한다.
     * 첨부 미리보기가 필요한 문서의 대부분이 PDF다.
     */
    @Test
    void PDF는_인라인으로_연다() throws Exception {
        byte[] pdf = "%PDF-1.4\ntest".getBytes(StandardCharsets.UTF_8);
        long id = upload("spec.pdf", pdf);

        mvc.perform(get("/api/wiki/attachments/{id}/inline", id).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("inline")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    /** SVG는 브라우저가 문서로 실행한다 — 우리 오리진에서 스크립트가 도는 길을 열지 않는다. */
    @Test
    void 알_수_없는_타입은_인라인으로_열리지_않는다() throws Exception {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script/></svg>"
                .getBytes(StandardCharsets.UTF_8);
        long id = upload("evil.svg", svg);

        mvc.perform(get("/api/wiki/attachments/{id}/inline", id).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isUnsupportedMediaType());
    }

    private long upload(String filename, byte[] bytes) throws Exception {
        String body = mvc.perform(multipart("/api/wiki/pages/{id}/attachments", page.getId())
                        .file(new MockMultipartFile("file", filename, MediaType.APPLICATION_OCTET_STREAM_VALUE, bytes))
                        .with(asUser(EDITOR, "Alice")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asLong();
    }

    /** 헤더만 진짜 PNG이면 detect()가 image/png로 읽는다. 뒤의 한 바이트로 두 버전을 구분한다. */
    private static byte[] pngBytes(byte marker) {
        byte[] header = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        byte[] out = new byte[header.length + 1];
        System.arraycopy(header, 0, out, 0, header.length);
        out[header.length] = marker;
        return out;
    }
}
