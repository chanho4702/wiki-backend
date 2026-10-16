package com.platform.wikibackend.space;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.SpaceLabelRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스페이스 라벨(V41) — 디렉토리 분류. 저장·조회·ADMIN 편집·정규화·상한. */
@SpringBootTest
@ActiveProfiles("test")
class SpaceLabelTest {

    static final long ADMIN = 1L;
    static final long VIEWER = 2L;

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired SpaceLabelRepository labels;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    MockMvc mvc;
    long spaceId;
    long otherSpaceId;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        labels.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        spaceId = spaces.save(Space.of("eng", "개발", null, ADMIN)).getId();
        otherSpaceId = spaces.save(Space.of("ops", "운영", null, ADMIN)).getId();
        for (long s : new long[]{spaceId, otherSpaceId}) {
            perms.allow(ADMIN, s, WikiAction.VIEW);
            perms.allow(ADMIN, s, WikiAction.ADMIN);
            perms.allow(VIEWER, s, WikiAction.VIEW);
        }
    }

    private org.springframework.test.web.servlet.ResultActions putLabels(long user, long space, String json) throws Exception {
        return mvc.perform(put("/api/wiki/spaces/" + space + "/labels").with(asUser(user, "u" + user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"labels\":" + json + "}"));
    }

    @Test
    void 관리자는_라벨을_정규화해_전량_교체하고_목록과_단건에_실린다() throws Exception {
        putLabels(ADMIN, spaceId, "[\" Engineering \", \"run book\", \"engineering\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labels.length()").value(2))
                .andExpect(jsonPath("$.labels[0]").value("engineering"))
                .andExpect(jsonPath("$.labels[1]").value("run-book"));

        mvc.perform(get("/api/wiki/spaces/" + spaceId).with(asUser(VIEWER, "Bob")))
                .andExpect(jsonPath("$.labels[0]").value("engineering"));
        mvc.perform(get("/api/wiki/spaces").with(asUser(VIEWER, "Bob")))
                .andExpect(jsonPath("$[?(@.key == 'eng')].labels[1]").value("run-book"))
                // 라벨이 없는 스페이스도 null이 아니라 빈 배열이다
                .andExpect(jsonPath("$[?(@.key == 'ops')].labels.length()").value(0));

        putLabels(ADMIN, spaceId, "[]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labels.length()").value(0));
        assertThat(labels.count()).isZero();
    }

    @Test
    void 관리자가_아니면_403() throws Exception {
        putLabels(VIEWER, spaceId, "[\"x\"]").andExpect(status().isForbidden());
        assertThat(labels.count()).isZero();
    }

    @Test
    void 상한과_빈_라벨은_400() throws Exception {
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i <= SpaceService.MAX_LABELS_PER_SPACE; i++) many.append(i == 0 ? "" : ",").append("\"l").append(i).append('"');
        putLabels(ADMIN, spaceId, many.append(']').toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("스페이스 라벨은 " + SpaceService.MAX_LABELS_PER_SPACE + "개까지입니다"));
        putLabels(ADMIN, spaceId, "[\"  \"]").andExpect(status().isBadRequest());
    }

    @Test
    void 스페이스를_지우면_라벨도_사라진다() throws Exception {
        putLabels(ADMIN, spaceId, "[\"a\"]").andExpect(status().isOk());
        putLabels(ADMIN, otherSpaceId, "[\"b\"]").andExpect(status().isOk());
        mvc.perform(delete("/api/wiki/spaces/" + spaceId).with(asUser(ADMIN, "Alice")))
                .andExpect(status().isNoContent());
        assertThat(labels.findAll()).extracting(l -> l.getName()).containsExactly("b");
    }
}
