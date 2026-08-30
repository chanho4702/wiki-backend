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

    @Test
    void 볼_수_없는_사용자는_받을_수_없다() throws Exception {
        Page page = pages.save(Page.of(space.getId(), null, "문서", "본문", USER));

        mvc.perform(get("/api/wiki/pages/{id}/export.pdf", page.getId()).with(asUser(STRANGER, "남")))
                .andExpect(status().isForbidden());
    }
}
