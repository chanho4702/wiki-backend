package com.platform.wikibackend.permission;

import com.platform.wikibackend.domain.PageRestriction;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.repository.NotificationRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRestrictionRepository;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * W18 페이지 제한 — 누출 테스트 매트릭스(설계서 §9).
 * 시나리오: ①space 권한 없음 ②조상 VIEW 제한 상속 ③현재 페이지 EDIT 제한(보기 무관)
 * ④TEAM 주체 판정 ⑤space ADMIN도 본문 비노출 + 경로별(본문/트리/댓글/첨부/조회수/티켓) 차단.
 */
@SpringBootTest
@ActiveProfiles("test")
class PageRestrictionLeakTest {

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired PageRestrictionRepository restrictions;
    @Autowired NotificationRepository notifications;
    @Autowired FakePermissionClient perms;
    @Autowired FakeTeamDirectory teams;
    MockMvc mvc;

    Space space;
    static final long ALICE = 1L; // 제한 목록에 드는 사용자
    static final long BOB = 2L;   // 스페이스 권한은 있지만 제한 밖
    static final long ADMIN = 3L; // 스페이스 ADMIN
    static final long TEAM_ID = 77L;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        restrictions.deleteAll();
        notifications.deleteAll();
        revisions.deleteAll();
        pages.deleteAllIncludingTrashed();
        spaces.deleteAll();
        perms.reset();
        teams.reset();
        space = spaces.save(Space.of("dev", "개발", null, ALICE));
        for (long u : new long[] {ALICE, BOB, ADMIN}) {
            perms.allow(u, space.getId(), WikiAction.VIEW);
            perms.allow(u, space.getId(), WikiAction.EDIT);
        }
        perms.allow(ADMIN, space.getId(), WikiAction.ADMIN);
    }

    private long createPage(Long parentId, String title) throws Exception {
        String parent = parentId == null ? "null" : String.valueOf(parentId);
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId() + ",\"parentId\":" + parent
                                + ",\"title\":\"" + title + "\",\"content\":\"본문\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
    }

    private void restrict(long pageId, PageRestriction.Type type,
                          PageRestriction.PrincipalType principalType, long principalId) {
        restrictions.save(PageRestriction.of(pageId, type, principalType, principalId, ALICE));
    }

    @Test
    void space_권한이_없으면_제한_목록에_있어도_거부된다() throws Exception {
        long id = createPage(null, "문서");
        long dave = 9L; // 스페이스 grant 없음
        restrict(id, PageRestriction.Type.VIEW, PageRestriction.PrincipalType.USER, dave);
        mvc.perform(get("/api/wiki/pages/" + id).with(asUser(dave, "데이브")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 조상_VIEW_제한은_자손에_상속되고_트리에서도_사라진다() throws Exception {
        long parent = createPage(null, "제한 문서");
        long child = createPage(parent, "하위 문서");
        long open = createPage(null, "공개 문서");
        restrict(parent, PageRestriction.Type.VIEW, PageRestriction.PrincipalType.USER, ALICE);

        // 본문: 자손까지 403, 제한 목록의 앨리스는 통과
        mvc.perform(get("/api/wiki/pages/" + child).with(asUser(BOB, "밥")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("이 페이지를 볼 권한이 없습니다"));
        mvc.perform(get("/api/wiki/pages/" + child).with(asUser(ALICE, "앨리스")))
                .andExpect(status().isOk());

        // 트리: 밥에게는 공개 문서만 보인다(제한 노드 + 자손 제외)
        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/pages").with(asUser(BOB, "밥")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(open));
        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/pages").with(asUser(ALICE, "앨리스")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void EDIT_제한은_수정만_좁히고_보기는_그대로다() throws Exception {
        long id = createPage(null, "문서");
        restrict(id, PageRestriction.Type.EDIT, PageRestriction.PrincipalType.USER, ALICE);

        mvc.perform(get("/api/wiki/pages/" + id).with(asUser(BOB, "밥")))
                .andExpect(status().isOk()); // EDIT 제한이 VIEW를 좁히지 않는다
        mvc.perform(put("/api/wiki/pages/" + id).with(asUser(BOB, "밥"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"수정\",\"content\":\"바꿈\",\"parentId\":null,\"expectedVersion\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("이 페이지를 수정할 권한이 없습니다"));
        mvc.perform(put("/api/wiki/pages/" + id).with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"수정\",\"content\":\"바꿈\",\"parentId\":null,\"expectedVersion\":1}"))
                .andExpect(status().isOk());
    }

    @Test
    void TEAM_주체는_팀_멤버십으로_판정한다() throws Exception {
        long id = createPage(null, "팀 문서");
        restrict(id, PageRestriction.Type.VIEW, PageRestriction.PrincipalType.TEAM, TEAM_ID);

        mvc.perform(get("/api/wiki/pages/" + id).with(asUser(BOB, "밥")))
                .andExpect(status().isForbidden()); // 팀 미소속(기본 TeamDirectory는 fail-closed)
        teams.join(BOB, TEAM_ID);
        mvc.perform(get("/api/wiki/pages/" + id).with(asUser(BOB, "밥")))
                .andExpect(status().isOk());
    }

    @Test
    void space_ADMIN도_제한_목록에_없으면_본문을_보지_못한다() throws Exception {
        long id = createPage(null, "제한 문서");
        restrict(id, PageRestriction.Type.VIEW, PageRestriction.PrincipalType.USER, ALICE);
        mvc.perform(get("/api/wiki/pages/" + id).with(asUser(ADMIN, "관리자")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 댓글_첨부_조회수_협업티켓_경로도_같은_함수로_막힌다() throws Exception {
        long id = createPage(null, "제한 문서");
        restrict(id, PageRestriction.Type.VIEW, PageRestriction.PrincipalType.USER, ALICE);

        mvc.perform(get("/api/wiki/pages/" + id + "/comments").with(asUser(BOB, "밥")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/wiki/pages/" + id + "/attachments").with(asUser(BOB, "밥")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/wiki/pages/" + id + "/views").with(asUser(BOB, "밥")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/wiki/pages/" + id + "/collaboration-ticket").with(asUser(BOB, "밥")))
                .andExpect(status().isForbidden());
        // 리비전 목록도 본문과 같은 급의 콘텐츠다
        mvc.perform(get("/api/wiki/pages/" + id + "/revisions").with(asUser(BOB, "밥")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 제한된_자손이_있으면_부모_삭제와_서브트리_이동도_거부된다() throws Exception {
        long parent = createPage(null, "공개 부모");
        long child = createPage(parent, "제한 자손");
        long target = createPage(null, "이동 대상");
        restrict(child, PageRestriction.Type.VIEW, PageRestriction.PrincipalType.USER, ALICE);

        mvc.perform(delete("/api/wiki/pages/" + parent).param("children", "cascade")
                        .with(asUser(BOB, "밥")))
                .andExpect(status().isForbidden());
        assertThat(pages.existsById(parent)).isTrue();
        assertThat(pages.existsById(child)).isTrue();

        mvc.perform(post("/api/wiki/pages/" + parent + "/move").with(asUser(BOB, "밥"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + target + ",\"confirmImpact\":true}"))
                .andExpect(status().isForbidden());
        assertThat(pages.findById(parent).orElseThrow().getParentId()).isNull();
    }

    @Test
    void 숨겨진_부모는_생성_이동_대상이_될_수_없고_PUT_부모변경도_막힌다() throws Exception {
        long restrictedParent = createPage(null, "숨겨진 부모");
        long moving = createPage(null, "옮길 문서");
        restrict(restrictedParent, PageRestriction.Type.VIEW, PageRestriction.PrincipalType.USER, ALICE);

        mvc.perform(post("/api/wiki/pages").with(asUser(BOB, "밥"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId() + ",\"parentId\":" + restrictedParent
                                + ",\"title\":\"침범 문서\",\"content\":\"본문\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/wiki/pages/" + moving + "/move").with(asUser(BOB, "밥"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + restrictedParent + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.impact").doesNotExist());

        mvc.perform(put("/api/wiki/pages/" + moving).with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"옮길 문서\",\"content\":\"본문\",\"parentId\":"
                                + restrictedParent + ",\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("부모 변경은 페이지 이동 API를 사용해야 합니다"));
    }
}
