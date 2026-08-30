package com.platform.wikibackend.audit;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.AuditLogRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 스페이스 삭제 기록(V30) — 스페이스보다 오래 남고, 전역 관리자만 읽는다. */
@SpringBootTest
@ActiveProfiles("test")
class SpaceDeletionAuditTest {

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired AuditLogRepository logs;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    MockMvc mvc;

    static final long ADMIN = 1L;
    static final long GLOBAL = 9L;
    static final long OTHER = 2L;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        logs.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        perms.allowAll(GLOBAL);
    }

    @Test
    void 지운_스페이스의_기록이_남고_전역_관리자만_읽는다() throws Exception {
        Space space = spaces.save(Space.of("ops", "운영", null, ADMIN));
        perms.allow(ADMIN, space.getId(), WikiAction.ADMIN);
        pages.save(Page.of(space.getId(), null, "문서", "본문", ADMIN));

        mvc.perform(delete("/api/wiki/spaces/{id}", space.getId()).with(asUser(ADMIN, "관리자")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/wiki/audit/space-deletions").with(asUser(GLOBAL, "전역")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].action").value("SPACE_DELETED"))
                .andExpect(jsonPath("$[0].targetLabel").value("운영 (ops)"))
                .andExpect(jsonPath("$[0].actorId").value(1))
                .andExpect(jsonPath("$[0].detail").value("문서 1건 함께 삭제"));

        mvc.perform(get("/api/wiki/audit/space-deletions").with(asUser(OTHER, "남")))
                .andExpect(status().isForbidden());
    }
}
