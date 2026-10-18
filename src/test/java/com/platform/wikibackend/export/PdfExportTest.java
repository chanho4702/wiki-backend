package com.platform.wikibackend.export;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PDF 내보내기(W26) — 한글이 실제 글자로 들어가는지(폰트 임베드), 하위 포함, 권한. */
@SpringBootTest
@ActiveProfiles("test")
class PdfExportTest {

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    MockMvc mvc;

    Space space;
    static final long USER = 1L;
    static final long STRANGER = 2L;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        space = spaces.save(Space.of("dev", "개발", null, USER));
        perms.allow(USER, space.getId(), WikiAction.VIEW);
    }

    private String textOf(byte[] pdf) throws Exception {
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void 한글_본문과_표가_실제_글자로_들어간다() throws Exception {
        Page page = pages.save(Page.of(space.getId(), null, "배포 안내", """
                # 개요

                이번 주 **배포 일정**입니다. :status[진행 중]{.info}

                | 서비스 | 담당자 |
                | --- | --- |
                | 게이트웨이 | [@김철수](user:1) |
                """, USER));

        byte[] pdf = mvc.perform(get("/api/wiki/pages/{id}/export.pdf", page.getId()).with(asUser(USER, "김")))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();

        String text = textOf(pdf);
        assertThat(text).contains("배포 안내").contains("배포 일정").contains("게이트웨이")
                .contains("진행 중")    // 상태 배지는 글자만
                .contains("@김철수");   // 멘션은 이름만
        assertThat(text).doesNotContain(":status").doesNotContain("user:1");
    }

    @Test
    void 하위_포함이면_자식_문서가_이어서_들어간다() throws Exception {
        Page parent = pages.save(Page.of(space.getId(), null, "부모", "부모 본문", USER));
        pages.save(Page.of(space.getId(), parent.getId(), "자식 문서", "자식 본문", USER));

        byte[] pdf = mvc.perform(get("/api/wiki/pages/{id}/export.pdf?includeChildren=true", parent.getId())
                        .with(asUser(USER, "김")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(textOf(pdf)).contains("부모 본문").contains("자식 문서").contains("자식 본문");
    }

    // ── W-06: 서버가 본문 이미지 URL을 가져오지 않는다(SSRF) ──

    /**
     * EDIT 권한만 있으면 본문에 내부망 주소를 이미지로 넣고 PDF를 내보내는 것만으로 서버가 그 주소에
     * GET을 보냈다(감사 W-06). 외부 URL은 어떤 것도 열지 않는다 — 요청 자체가 나가지 않아야 한다.
     */
    @Test
    void 본문의_외부_이미지_URL로는_요청이_나가지_않는다() throws Exception {
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer probe = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        probe.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] png = png();
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, png.length);
            exchange.getResponseBody().write(png);
            exchange.close();
        });
        probe.start();
        try {
            String url = "http://127.0.0.1:" + probe.getAddress().getPort();
            Page page = pages.save(Page.of(space.getId(), null, "외부 이미지", "본문\n\n![내부](" + url
                    + "/latest/meta-data.png)\n\n![상대](//127.0.0.1:" + probe.getAddress().getPort()
                    + "/x.png)\n\n![파일](file:///etc/passwd)\n\n끝 문단", USER));

            byte[] pdf = mvc.perform(get("/api/wiki/pages/{id}/export.pdf", page.getId()).with(asUser(USER, "김")))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();

            assertThat(hits.get()).isZero();
            assertThat(textOf(pdf)).contains("끝 문단");
            assertThat(imageCount(pdf)).isZero();
        } finally {
            probe.stop(0);
        }
    }

    /**
     * 위키 자체 첨부 이미지는 상대 경로(/api/wiki/attachments/{id}/inline)라 예전엔 PDF에 아예 안 나왔다.
     * 저장소에서 바로 읽어 PDF에 넣는다 — 볼 수 있는 문서의 첨부일 때만.
     */
    @Test
    void 위키_첨부_이미지는_저장소에서_읽어_PDF에_넣는다() throws Exception {
        perms.allow(USER, space.getId(), WikiAction.EDIT);
        Page page = pages.save(Page.of(space.getId(), null, "첨부 이미지", "본문", USER));
        long attachmentId = uploadPng(page.getId());
        page.rewriteContentForCopy("본문\n\n![그림](/api/wiki/attachments/" + attachmentId + "/inline)\n");
        pages.save(page);

        byte[] pdf = mvc.perform(get("/api/wiki/pages/{id}/export.pdf", page.getId()).with(asUser(USER, "김")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(imageCount(pdf)).isEqualTo(1);
    }

    /** 다른(볼 수 없는) 문서의 첨부 id를 본문에 적어도 그 이미지는 들어가지 않는다. */
    @Test
    void 볼_수_없는_문서의_첨부_이미지는_넣지_않는다() throws Exception {
        perms.allow(USER, space.getId(), WikiAction.EDIT);
        Space secret = spaces.save(Space.of("secret", "비밀", null, STRANGER));
        perms.allow(STRANGER, secret.getId(), WikiAction.VIEW);
        perms.allow(STRANGER, secret.getId(), WikiAction.EDIT);
        Page hidden = pages.save(Page.of(secret.getId(), null, "비밀 문서", "본문", STRANGER));
        long secretAttachment = uploadPng(hidden.getId(), STRANGER);
        Page mine = pages.save(Page.of(space.getId(), null, "내 문서",
                "![훔치기](/api/wiki/attachments/" + secretAttachment + "/inline)\n\n끝", USER));

        byte[] pdf = mvc.perform(get("/api/wiki/pages/{id}/export.pdf", mine.getId()).with(asUser(USER, "김")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(imageCount(pdf)).isZero();
    }

    private long uploadPng(long pageId) throws Exception {
        return uploadPng(pageId, USER);
    }

    private long uploadPng(long pageId, long userId) throws Exception {
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/wiki/pages/{id}/attachments", pageId)
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "dot.png", "image/png", png()))
                        .with(asUser(userId, "u" + userId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
    }

    private static byte[] png() {
        try {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static int imageCount(byte[] pdf) throws Exception {
        int count = 0;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (var p : doc.getPages()) {
                var resources = p.getResources();
                for (var name : resources.getXObjectNames()) {
                    if (resources.isImageXObject(name)) count++;
                }
            }
        }
        return count;
    }

    @Test
    void 볼_수_없는_사용자는_받을_수_없다() throws Exception {
        Page page = pages.save(Page.of(space.getId(), null, "문서", "본문", USER));

        mvc.perform(get("/api/wiki/pages/{id}/export.pdf", page.getId()).with(asUser(STRANGER, "남")))
                .andExpect(status().isForbidden());
    }
}
