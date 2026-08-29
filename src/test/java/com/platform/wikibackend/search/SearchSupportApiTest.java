package com.platform.wikibackend.search;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageLabel;
import com.platform.wikibackend.domain.PageRestriction;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageLabelRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRestrictionRepository;
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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 검색 화면이 쓰는 보조 API — 결과 경로와 라벨 자동완성.
 *
 * 둘 다 검색 엔진을 타지 않는다. 그래서 OpenSearch 배포와 라이트 배포가 **같은 답**을 낸다 —
 * 색인에 경로를 넣었다면 문서를 옮길 때마다 하위 전체를 재색인해야 했고, 두 배포가 서로 다른
 * 길을 타게 됐을 것이다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SearchSupportApiTest {

    private static final long USER = 7L;
    private static final long OTHER = 8L;

    @Autowired WebApplicationContext context;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageLabelRepository labels;
    @Autowired PageRestrictionRepository restrictions;
    @Autowired FakePermissionClient permissions;

    MockMvc mvc;
    Space space;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        restrictions.deleteAll();
        labels.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        permissions.reset();

        space = spaces.save(Space.of("ENG", "개발 위키", "설명", USER));
        permissions.allow(USER, space.getId(), WikiAction.VIEW);
    }

    @Test
    void 경로는_루트부터_부모까지고_자기_자신은_빼다() throws Exception {
        Page root = page(null, "안내");
        Page mid = page(root.getId(), "배포");
        Page leaf = page(mid.getId(), "롤백 절차");

        mvc.perform(get("/api/wiki/pages/paths").param("id", String.valueOf(leaf.getId()))
                        .with(asUser(USER, "u7")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].titles.length()").value(2))
                .andExpect(jsonPath("$[0].titles[0]").value("안내"))
                .andExpect(jsonPath("$[0].titles[1]").value("배포"));
    }

    @Test
    void 루트_문서의_경로는_비어_있다() throws Exception {
        Page root = page(null, "안내");

        mvc.perform(get("/api/wiki/pages/paths").param("id", String.valueOf(root.getId()))
                        .with(asUser(USER, "u7")))
                .andExpect(jsonPath("$[0].titles.length()").value(0));
    }

    /** 경로만 흘려도 제한된 문서의 위치와 제목이 샌다 — 아예 답에 넣지 않는다. */
    @Test
    void 볼_수_없는_문서는_경로를_주지_않는다() throws Exception {
        Page open = page(null, "안내");
        Page closed = page(null, "비밀");
        permissions.allow(OTHER, space.getId(), WikiAction.VIEW);
        restrictions.save(PageRestriction.of(closed.getId(), PageRestriction.Type.VIEW,
                PageRestriction.PrincipalType.USER, USER, USER));

        mvc.perform(get("/api/wiki/pages/paths")
                        .param("id", String.valueOf(open.getId()))
                        .param("id", String.valueOf(closed.getId()))
                        .with(asUser(OTHER, "u8")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(open.getId()));
    }

    @Test
    void 라벨_자동완성은_접두_일치로_건수와_함께_준다() throws Exception {
        Page a = page(null, "문서 A");
        Page b = page(null, "문서 B");
        labels.save(PageLabel.of(a.getId(), "설계", USER));
        labels.save(PageLabel.of(b.getId(), "설계", USER));
        labels.save(PageLabel.of(a.getId(), "설정", USER));
        labels.save(PageLabel.of(a.getId(), "회의", USER));

        mvc.perform(get("/api/wiki/labels").param("q", "설").with(asUser(USER, "u7")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // 많이 쓰인 라벨이 먼저 — 고르라고 띄우는 목록이다
                .andExpect(jsonPath("$[0].name").value("설계"))
                .andExpect(jsonPath("$[0].count").value(2));
    }

    /** 질의도 저장할 때와 같은 규칙으로 정규화한다 — 대소문자만 달라 안 걸리면 이유를 모른다. */
    @Test
    void 라벨_자동완성_질의를_정규화한다() throws Exception {
        Page a = page(null, "문서 A");
        labels.save(PageLabel.of(a.getId(), "wave-d", USER));

        mvc.perform(get("/api/wiki/labels").param("q", "Wave D").with(asUser(USER, "u7")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("wave-d"));
    }

    @Test
    void 볼_수_없는_스페이스의_라벨은_후보에_없다() throws Exception {
        Page a = page(null, "문서 A");
        labels.save(PageLabel.of(a.getId(), "설계", USER));

        mvc.perform(get("/api/wiki/labels").param("q", "").with(asUser(OTHER, "u8")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private Page page(Long parentId, String title) {
        return pages.save(Page.of(space.getId(), parentId, title, "본문", USER));
    }
}
