package com.platform.wikibackend.mdimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Markdown 가져오기 — 제목 규칙, 부모·상태, 검증(확장자·MIME·UTF-8·개수), 권한, 전부 아니면 전무. */
@SpringBootTest
@ActiveProfiles("test")
class MarkdownImportTest {

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    MockMvc mvc;

    Space space;
    static final long USER = 1L;
    static final long READER = 2L;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        space = spaces.save(Space.of("dev", "개발", null, USER));
        perms.allow(USER, space.getId(), WikiAction.VIEW);
        perms.allow(USER, space.getId(), WikiAction.EDIT);
        perms.allow(READER, space.getId(), WikiAction.VIEW);
    }

    private static MockMultipartFile md(String name, String body) {
        return new MockMultipartFile("files", name, "text/markdown", body.getBytes(StandardCharsets.UTF_8));
    }

    private MockMultipartHttpServletRequestBuilder request() {
        return multipart("/api/wiki/spaces/{spaceId}/import/markdown", space.getId());
    }

    private List<Page> pagesInSpace() {
        return pages.findAll().stream()
                .filter(p -> p.getSpaceId().equals(space.getId()))
                .sorted(Comparator.comparing(Page::getId))
                .toList();
    }

    @Test
    void 파일마다_문서가_부모_아래에_생기고_제목은_앞머리_제목줄_파일이름_순이다() throws Exception {
        Page parent = pages.save(Page.of(space.getId(), null, "가져온 문서", "", USER));

        String body = mvc.perform(request()
                        .file(md("a.md", "---\ntitle: \"앞머리 제목\"\ntags: [x]\n---\n\n본문 A\n"))
                        .file(md("b.md", "﻿# 제목 줄\r\n\r\n본문 **B**\r\n"))
                        .file(md("dir\\설치 안내.markdown", "그냥 본문"))
                        .param("parentId", String.valueOf(parent.getId()))
                        .with(asUser(USER, "김")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pages.length()").value(3))
                .andExpect(jsonPath("$.warnings.length()").value(0))
                .andReturn().getResponse().getContentAsString();

        JsonNode created = new ObjectMapper().readTree(body).get("pages");
        assertThat(created.get(0).get("title").asText()).isEqualTo("앞머리 제목");
        assertThat(created.get(0).get("content").asText()).isEqualTo("본문 A\n");
        assertThat(created.get(1).get("title").asText()).isEqualTo("제목 줄");
        assertThat(created.get(1).get("content").asText()).isEqualTo("본문 **B**\n"); // BOM·CRLF 정리, 제목 줄 제거
        assertThat(created.get(2).get("title").asText()).isEqualTo("설치 안내");
        for (JsonNode p : created) {
            assertThat(p.get("parentId").asLong()).isEqualTo(parent.getId());
            assertThat(p.get("status").asText()).isEqualTo("published");
            assertThat(p.get("version").asInt()).isEqualTo(1);
        }
        // 올린 순서대로 형제 맨 뒤에
        assertThat(created.get(0).get("position").asLong()).isLessThan(created.get(1).get("position").asLong());
    }

    @Test
    void 초안으로_가져오면_다른_사람에게_보이지_않는다() throws Exception {
        String body = mvc.perform(request().file(md("draft.md", "# 비공개\n\n초안 본문"))
                        .param("status", "draft")
                        .with(asUser(USER, "김")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pages[0].status").value("draft"))
                .andReturn().getResponse().getContentAsString();
        long id = new ObjectMapper().readTree(body).get("pages").get(0).get("id").asLong();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/wiki/pages/{id}", id).with(asUser(READER, "남")))
                .andExpect(status().is4xxClientError());
        assertThat(pages.findById(id).orElseThrow().getStatus()).isEqualTo(PageStatus.DRAFT);
    }

    @Test
    void 상대_경로_이미지는_경고로_알리고_링크는_그대로_둔다() throws Exception {
        mvc.perform(request().file(md("img.md",
                        "![로컬](./images/a.png)\n![절대](https://example.com/b.png)\n![<공백>](<img/c d.png>)\n"))
                        .with(asUser(USER, "김")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warnings[0].file").value("img.md"))
                .andExpect(jsonPath("$.warnings[0].message").value(org.hamcrest.Matchers.containsString("2개")))
                .andExpect(jsonPath("$.pages[0].content").value(org.hamcrest.Matchers.containsString("./images/a.png")));
    }

    @Test
    void 하나라도_거부되면_아무_문서도_만들지_않는다() throws Exception {
        mvc.perform(request()
                        .file(md("ok.md", "# 좋음"))
                        .file(new MockMultipartFile("files", "photo.png", "image/png", new byte[]{1, 2, 3}))
                        .with(asUser(USER, "김")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("photo.png")));
        assertThat(pagesInSpace()).isEmpty();
    }

    @Test
    void 확장자가_맞아도_MIME이_명백히_다르거나_UTF8이_아니면_거부한다() throws Exception {
        mvc.perform(request().file(new MockMultipartFile("files", "x.md", "application/zip", "PK".getBytes()))
                        .with(asUser(USER, "김")))
                .andExpect(status().isBadRequest());
        mvc.perform(request().file(new MockMultipartFile("files", "x.md", "", new byte[]{(byte) 0xC3, (byte) 0x28}))
                        .with(asUser(USER, "김")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("UTF-8")));
        mvc.perform(request().file(new MockMultipartFile("files", "x.md", "application/octet-stream",
                        "a\0b".getBytes(StandardCharsets.UTF_8)))
                        .with(asUser(USER, "김")))
                .andExpect(status().isBadRequest());
        assertThat(pagesInSpace()).isEmpty();
    }

    @Test
    void 파일_수와_크기_상한() throws Exception {
        MockMultipartHttpServletRequestBuilder many = request();
        for (int i = 0; i <= MarkdownImportService.MAX_FILES; i++) many.file(md(i + ".md", "본문"));
        mvc.perform(many.with(asUser(USER, "김"))).andExpect(status().isBadRequest());

        byte[] big = new byte[(int) MarkdownImportService.MAX_FILE_BYTES + 1];
        java.util.Arrays.fill(big, (byte) 'a');
        mvc.perform(request().file(new MockMultipartFile("files", "big.md", "text/markdown", big))
                        .with(asUser(USER, "김")))
                .andExpect(status().isBadRequest());
        assertThat(pagesInSpace()).isEmpty();
    }

    @Test
    void 편집_권한이_없으면_403() throws Exception {
        mvc.perform(request().file(md("a.md", "본문")).with(asUser(READER, "남")))
                .andExpect(status().isForbidden());
        assertThat(pagesInSpace()).isEmpty();
    }

    @Test
    void 다른_스페이스의_부모는_거부한다() throws Exception {
        Space other = spaces.save(Space.of("other", "다른", null, USER));
        perms.allow(USER, other.getId(), WikiAction.VIEW);
        perms.allow(USER, other.getId(), WikiAction.EDIT);
        Page foreign = pages.save(Page.of(other.getId(), null, "남의 부모", "", USER));

        mvc.perform(request().file(md("a.md", "본문"))
                        .param("parentId", String.valueOf(foreign.getId()))
                        .with(asUser(USER, "김")))
                .andExpect(status().is4xxClientError());
        assertThat(pagesInSpace()).isEmpty();
    }

    /** Codex P2 — `---\n---`(빈 앞머리)가 substring(4, 3)으로 묶음 전체를 500으로 떨어뜨렸다. */
    @Test
    void 빈_앞머리는_빈_메타_블록으로_본다() throws Exception {
        mvc.perform(request()
                        .file(md("empty-front.md", "---\n---\n# 빈 앞머리 뒤 제목\n\n본문"))
                        .file(md("only-front.md", "---\n---"))
                        .with(asUser(USER, "김")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pages[0].title").value("빈 앞머리 뒤 제목"))
                .andExpect(jsonPath("$.pages[0].content").value("본문"))
                .andExpect(jsonPath("$.pages[1].title").value("only-front"))
                .andExpect(jsonPath("$.pages[1].content").value(""));
    }

    @Test
    void 제목_파싱_단위() {
        assertThat(MarkdownImportService.parse("x.md", "# 제목 #\n본문").title()).isEqualTo("제목");
        assertThat(MarkdownImportService.parse("x.md", "## 두번째 수준\n본문").title()).isEqualTo("x");
        assertThat(MarkdownImportService.parse("x.md", "---\ntitle: 'a'\n---").body()).isEmpty();
        assertThat(MarkdownImportService.parse(".md", "").title()).isEqualTo("제목 없음");
        assertThat(MarkdownImportService.parse("e.md", "---\n---\n").title()).isEqualTo("e");
        assertThat(MarkdownImportService.parse("e.md", "---\n---\n").body()).isEmpty();
        assertThat(MarkdownImportService.parse("x.md", "# " + "가".repeat(300)).title()).hasSize(255);
    }
}
