package com.platform.wikibackend.export;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.domain.PageType;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.util.List;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Word 내보내기 — 구조(제목 스타일·표·코드), 확장 대체, 이미지 임베드·SSRF, 권한·초안 가시성. */
@SpringBootTest
@ActiveProfiles("test")
class DocxExportTest {

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

    private byte[] export(long pageId, long userId, String query) throws Exception {
        return mvc.perform(get("/api/wiki/pages/{id}/export.docx" + query, pageId).with(asUser(userId, "u")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment; filename*=UTF-8''")))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private static XWPFDocument open(byte[] docx) throws Exception {
        assertThat(new String(docx, 0, 2)).isEqualTo("PK"); // OOXML = zip
        return new XWPFDocument(new ByteArrayInputStream(docx));
    }

    private static String textOf(XWPFDocument doc) throws Exception {
        try (XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }

    @Test
    void 제목_표_코드_목록이_Word_구조로_들어간다() throws Exception {
        Page page = pages.save(Page.of(space.getId(), null, "배포 안내", """
                # 개요

                이번 주 **배포 일정**입니다. :status[진행 중]{.info} 담당 [@김철수](user:1)

                - 첫째
                - [x] 끝낸 일

                | 서비스 | 담당자 |
                | --- | --- |
                | 게이트웨이 | 박 |

                ```java
                int x = 1;
                ```

                ```mermaid
                graph TD; A-->B
                ```

                $$
                E = mc^2
                $$

                <script>alert(1)</script>

                [외부](https://example.com) [내부](/wiki/spaces/1/pages/2)
                """, USER));

        try (XWPFDocument doc = open(export(page.getId(), USER, ""))) {
            List<XWPFParagraph> paragraphs = doc.getParagraphs();
            assertThat(paragraphs.get(0).getStyle()).isEqualTo("Title");
            assertThat(paragraphs.get(0).getText()).isEqualTo("배포 안내");
            assertThat(paragraphs).anySatisfy(p -> {
                assertThat(p.getStyle()).isEqualTo("Heading1");
                assertThat(p.getText()).isEqualTo("개요");
            });
            assertThat(doc.getStyles().styleExist("Heading1")).isTrue();

            assertThat(doc.getTables()).hasSize(1);
            assertThat(doc.getTables().get(0).getRow(1).getCell(0).getText()).isEqualTo("게이트웨이");

            String text = textOf(doc);
            assertThat(text).contains("배포 일정").contains("진행 중").contains("@김철수")
                    .contains("☑").contains("int x = 1;")
                    .contains("Mermaid 다이어그램").contains("graph TD; A-->B")
                    .contains("수식 (LaTeX)").contains("E = mc^2")
                    .contains("<script>alert(1)</script>"); // raw HTML은 글자로
            assertThat(text).doesNotContain(":status").doesNotContain("user:1");

            // 외부 링크만 하이퍼링크, 위키 내부 상대 링크는 글자
            assertThat(doc.getHyperlinks()).extracting(h -> h.getURL()).containsExactly("https://example.com");
        }
    }

    @Test
    void 하위_포함이면_자식_문서가_새_쪽에서_이어진다() throws Exception {
        Page parent = pages.save(Page.of(space.getId(), null, "부모", "부모 본문", USER));
        pages.save(Page.of(space.getId(), parent.getId(), "자식 문서", "자식 본문", USER));

        try (XWPFDocument doc = open(export(parent.getId(), USER, "?includeChildren=true"))) {
            assertThat(textOf(doc)).contains("부모 본문").contains("자식 문서").contains("자식 본문");
            assertThat(doc.getParagraphs()).anySatisfy(p -> {
                assertThat(p.getText()).isEqualTo("자식 문서");
                assertThat(p.isPageBreak()).isTrue();
            });
        }
    }

    @Test
    void 남의_초안은_하위_포함에서도_빠진다() throws Exception {
        perms.allow(STRANGER, space.getId(), WikiAction.VIEW);
        Page parent = pages.save(Page.of(space.getId(), null, "부모", "부모 본문", STRANGER));
        pages.save(Page.of(space.getId(), parent.getId(), "남의 초안", "숨은 본문", USER,
                PageType.PAGE, PageStatus.DRAFT));

        try (XWPFDocument doc = open(export(parent.getId(), STRANGER, "?includeChildren=true"))) {
            assertThat(textOf(doc)).contains("부모 본문").doesNotContain("숨은 본문");
        }
    }

    @Test
    void 남의_초안_자체는_받을_수_없다() throws Exception {
        perms.allow(STRANGER, space.getId(), WikiAction.VIEW);
        Page draft = pages.save(Page.of(space.getId(), null, "초안", "본문", USER, PageType.PAGE, PageStatus.DRAFT));

        mvc.perform(get("/api/wiki/pages/{id}/export.docx", draft.getId()).with(asUser(STRANGER, "남")))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void 볼_수_없는_사용자는_받을_수_없다() throws Exception {
        Page page = pages.save(Page.of(space.getId(), null, "문서", "본문", USER));

        mvc.perform(get("/api/wiki/pages/{id}/export.docx", page.getId()).with(asUser(STRANGER, "남")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 위키_첨부_이미지는_심고_외부_이미지는_열지_않는다() throws Exception {
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer probe = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        probe.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        probe.start();
        try {
            perms.allow(USER, space.getId(), WikiAction.EDIT);
            Page page = pages.save(Page.of(space.getId(), null, "이미지", "본문", USER));
            long attachmentId = uploadPng(page.getId(), USER);
            page.rewriteContentForCopy("![그림](/api/wiki/attachments/" + attachmentId + "/inline)\n\n"
                    + "![내부망](http://127.0.0.1:" + probe.getAddress().getPort() + "/x.png)\n\n끝 문단\n");
            pages.save(page);

            try (XWPFDocument doc = open(export(page.getId(), USER, ""))) {
                assertThat(doc.getAllPictures()).hasSize(1);
                assertThat(textOf(doc)).contains("[이미지: 내부망]").contains("끝 문단");
            }
            assertThat(hits.get()).isZero();
        } finally {
            probe.stop(0);
        }
    }

    @Test
    void 볼_수_없는_문서의_첨부_이미지는_심지_않는다() throws Exception {
        Space secret = spaces.save(Space.of("secret", "비밀", null, STRANGER));
        perms.allow(STRANGER, secret.getId(), WikiAction.VIEW);
        perms.allow(STRANGER, secret.getId(), WikiAction.EDIT);
        Page hidden = pages.save(Page.of(secret.getId(), null, "비밀 문서", "본문", STRANGER));
        long secretAttachment = uploadPng(hidden.getId(), STRANGER);
        Page mine = pages.save(Page.of(space.getId(), null, "내 문서",
                "![훔치기](/api/wiki/attachments/" + secretAttachment + "/inline)", USER));

        try (XWPFDocument doc = open(export(mine.getId(), USER, ""))) {
            assertThat(doc.getAllPictures()).isEmpty();
            assertThat(textOf(doc)).contains("[이미지: 훔치기]");
        }
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
}
