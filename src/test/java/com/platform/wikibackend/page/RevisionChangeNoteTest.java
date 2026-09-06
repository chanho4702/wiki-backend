package com.platform.wikibackend.page;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
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

import static com.platform.wikibackend.TestAuth.asUser;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 변경 요약(W22, V17) — 버전이 수십 개가 되면 누가·언제만으로는 어느 것이 되돌릴 지점인지 모른다.
 * 선택 입력이다: 강제하면 "수정"만 적힌 이력이 쌓여 오히려 신호가 죽는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class RevisionChangeNoteTest {

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    MockMvc mvc;

    Space space;
    long pageId;
    static final long EDITOR = 1L;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        revisions.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        space = spaces.save(Space.of("dev", "개발", null, EDITOR));
        perms.allow(EDITOR, space.getId(), WikiAction.VIEW);
        perms.allow(EDITOR, space.getId(), WikiAction.EDIT);
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId()
                                + ",\"parentId\":null,\"title\":\"보고서\",\"content\":\"처음\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        pageId = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
    }

    private void update(String content, int expectedVersion, String noteJson) throws Exception {
        mvc.perform(put("/api/wiki/pages/" + pageId).with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"보고서\",\"content\":\"" + content
                                + "\",\"expectedVersion\":" + expectedVersion + noteJson + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void 저장할_때_남긴_요약이_그_버전의_이력에_붙는다() throws Exception {
        update("고침", 1, ",\"changeNote\":\"오타 수정\"");

        mvc.perform(get("/api/wiki/pages/" + pageId + "/revisions").with(asUser(EDITOR, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].changeNote").value("오타 수정"))
                .andExpect(jsonPath("$[1].version").value(1))
                .andExpect(jsonPath("$[1].changeNote").doesNotExist());
    }

    /** 공백만 적은 요약은 없는 것과 같다 — 화면이 빈 칩을 그리지 않아야 한다. */
    @Test
    void 공백만_있는_요약은_없는_것으로_저장한다() throws Exception {
        update("고침", 1, ",\"changeNote\":\"   \"");

        mvc.perform(get("/api/wiki/pages/" + pageId + "/revisions").with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$[0].changeNote").doesNotExist());
    }

    @Test
    void 요약은_선택이라_없어도_저장된다() throws Exception {
        update("고침", 1, "");

        mvc.perform(get("/api/wiki/pages/" + pageId + "/revisions").with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].changeNote").doesNotExist());
    }

    /** 어느 버전에서 되돌렸는지가 다음 사람에게 가장 중요한 정보다. */
    @Test
    void 복원은_어느_버전에서_되돌렸는지를_이력에_남긴다() throws Exception {
        update("2번째", 1, "");
        update("3번째", 2, "");

        mvc.perform(post("/api/wiki/pages/" + pageId + "/revisions/1/restore")
                        .with(asUser(EDITOR, "Alice")))
                .andExpect(status().isOk());

        mvc.perform(get("/api/wiki/pages/" + pageId + "/revisions").with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$[0].version").value(4))
                .andExpect(jsonPath("$[0].changeNote").value("v1 버전으로 복원"));
    }

    /**
     * 본문 없는 POST — wiki-front가 changeNote 없이 부를 때의 모양이다(어댑터가 본문을 아예 싣지
     * 않는다). Content-Type도 없으므로 그 요청이 415로 튕기지 않는지까지 함께 본다.
     */
    @Test
    void 복원_본문이_없으면_기본_요약이_남는다() throws Exception {
        update("2번째", 1, "");

        mvc.perform(post("/api/wiki/pages/" + pageId + "/revisions/1/restore")
                        .with(asUser(EDITOR, "Alice")))
                .andExpect(status().isOk());

        mvc.perform(get("/api/wiki/pages/" + pageId + "/revisions").with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$[0].changeNote").value("v1 버전으로 복원"));
    }

    @Test
    void 복원_요약을_보내면_그것이_이력에_남는다() throws Exception {
        update("2번째", 1, "");

        mvc.perform(post("/api/wiki/pages/" + pageId + "/revisions/1/restore")
                        .with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeNote\":\"장애 전 상태로 되돌림\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/wiki/pages/" + pageId + "/revisions").with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$[0].version").value(3))
                .andExpect(jsonPath("$[0].changeNote").value("장애 전 상태로 되돌림"));
    }

    /** 공백만 적은 것은 안 적은 것이다 — 빈 요약을 남기면 화면이 빈 칩을 그린다. */
    @Test
    void 복원_요약이_공백뿐이면_기본_요약으로_돌아간다() throws Exception {
        update("2번째", 1, "");

        mvc.perform(post("/api/wiki/pages/" + pageId + "/revisions/1/restore")
                        .with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeNote\":\"   \"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/wiki/pages/" + pageId + "/revisions").with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$[0].changeNote").value("v1 버전으로 복원"));
    }

    @Test
    void 복원_요약도_길이_상한을_지킨다() throws Exception {
        mvc.perform(post("/api/wiki/pages/" + pageId + "/revisions/1/restore")
                        .with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeNote\":\"" + "가".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 너무_긴_요약은_거부한다() throws Exception {
        String tooLong = "가".repeat(501);
        mvc.perform(put("/api/wiki/pages/" + pageId).with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"보고서\",\"content\":\"고침\",\"expectedVersion\":1,"
                                + "\"changeNote\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * 편집자 이름 스냅샷(V28). 디렉터리에서 사라진 사람(퇴사)도 이름으로 남아야 6개월 전 누가
     * 고쳤는지가 숫자가 아니라 이름으로 보인다.
     */
    @Test
    void 리비전에_저장_시점_편집자_이름이_남는다() throws Exception {
        Page page = pages.save(Page.of(space.getId(), null, "문서", "본문", EDITOR));

        mvc.perform(put("/api/wiki/pages/{id}", page.getId())
                        .with(asUser(EDITOR, "김철수"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"문서\",\"content\":\"고침\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/wiki/pages/{id}/revisions", page.getId()).with(asUser(EDITOR, "김철수")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].editedByName").value("김철수"));
    }
}
