package com.platform.wikibackend.page;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.AuditLogRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
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

import java.time.LocalDate;
import java.time.ZoneOffset;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 페이지 소유자·검증(W27-5). */
@SpringBootTest
@ActiveProfiles("test")
class PageOwnerVerificationTest {

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired AuditLogRepository auditLogs;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    MockMvc mvc;

    Space space;
    long pageId;
    static final long AUTHOR = 1L;
    static final long OWNER = 2L;
    static final long READER = 3L;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        auditLogs.deleteAll();
        revisions.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        space = spaces.save(Space.of("dev", "개발", null, AUTHOR));
        perms.allow(AUTHOR, space.getId(), WikiAction.VIEW);
        perms.allow(AUTHOR, space.getId(), WikiAction.EDIT);
        perms.allow(READER, space.getId(), WikiAction.VIEW); // 보기만 — 검증은 못 누른다
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId()
                                + ",\"parentId\":null,\"title\":\"운영 가이드\",\"content\":\"본문\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        pageId = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
    }

    @Test
    void 새_문서에는_소유자도_검증도_없다() throws Exception {
        // created_by를 소유자로 복사하지 않는다 — "정하지 않음"이 유효한 상태다
        mvc.perform(get("/api/wiki/pages/" + pageId).with(asUser(AUTHOR, "Alice")))
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.verifiedAt").doesNotExist())
                .andExpect(jsonPath("$.verifiedUntil").doesNotExist());
    }

    @Test
    void 소유자를_지정하고_해제할_수_있다() throws Exception {
        mvc.perform(put("/api/wiki/pages/" + pageId + "/owner").with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ownerId\":" + OWNER + "}"))
                .andExpect(jsonPath("$.ownerId").value((int) OWNER))
                // 메타데이터 변경이라 버전은 그대로다(아이콘·이동과 같은 취급)
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(put("/api/wiki/pages/" + pageId + "/owner").with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ownerId\":null}"))
                .andExpect(jsonPath("$.ownerId").doesNotExist());

        assertThat(auditLogs.findAll()).extracting("action")
                .contains("PAGE_OWNER_CHANGED");
    }

    @Test
    void 검증하면_유효기간이_기본_90일로_찍힌다() throws Exception {
        String body = mvc.perform(put("/api/wiki/pages/" + pageId + "/verification").with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verifiedBy").value((int) AUTHOR))
                .andExpect(jsonPath("$.verifiedAt").exists())
                .andReturn().getResponse().getContentAsString();

        String until = com.jayway.jsonpath.JsonPath.parse(body).read("$.verifiedUntil", String.class);
        LocalDate expected = LocalDate.now(ZoneOffset.UTC).plusDays(PageService.VERIFICATION_DAYS);
        assertThat(until).startsWith(expected.toString());
    }

    @Test
    void 유효기간을_직접_고를_수_있고_해제하면_전부_비워진다() throws Exception {
        mvc.perform(put("/api/wiki/pages/" + pageId + "/verification").with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verifiedUntil\":\"2026-12-03\"}"))
                .andExpect(jsonPath("$.verifiedUntil").value(org.hamcrest.Matchers.startsWith("2026-12-03")));

        mvc.perform(delete("/api/wiki/pages/" + pageId + "/verification").with(asUser(AUTHOR, "Alice")))
                .andExpect(jsonPath("$.verifiedAt").doesNotExist())
                .andExpect(jsonPath("$.verifiedBy").doesNotExist())
                .andExpect(jsonPath("$.verifiedUntil").doesNotExist());

        assertThat(auditLogs.findAll()).extracting("action")
                .contains("PAGE_VERIFIED", "PAGE_UNVERIFIED");
    }

    /** 지난 날짜도 저장한다 — 만료 판정은 읽는 쪽이 하고, 서버는 사람이 누른 사실만 남긴다. */
    @Test
    void 이미_지난_유효기간도_그대로_저장한다() throws Exception {
        mvc.perform(put("/api/wiki/pages/" + pageId + "/verification").with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verifiedUntil\":\"2020-01-01\"}"))
                .andExpect(jsonPath("$.verifiedUntil").value(org.hamcrest.Matchers.startsWith("2020-01-01")));
    }

    @Test
    void 편집_권한이_없으면_소유자도_검증도_바꿀_수_없다() throws Exception {
        mvc.perform(put("/api/wiki/pages/" + pageId + "/owner").with(asUser(READER, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ownerId\":" + READER + "}"))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/wiki/pages/" + pageId + "/verification").with(asUser(READER, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 소유자_id가_올바르지_않으면_거부한다() throws Exception {
        mvc.perform(put("/api/wiki/pages/" + pageId + "/owner").with(asUser(AUTHOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ownerId\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("소유자 id가 올바르지 않습니다"));
    }
}
