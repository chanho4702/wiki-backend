package com.platform.wikibackend.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.NotificationRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스페이스를 가로지르는 문서 피드 — 사람별 최근 문서(F-07)·인기·팔로잉(2026-10-24).
 *
 * 가시성은 트리·검색과 같은 판정(스페이스 VIEW + 페이지 제한 + 초안 정책)이어야 한다 — 피드가 그 판정을
 * 비켜 가면 남의 초안 제목이 프로필·홈으로 샌다.
 */
@SpringBootTest
@ActiveProfiles("test")
class FeedApiTest {

    static final long AUTHOR = 1L;
    static final long READER = 2L;       // 같은 스페이스 편집자 — 남의 초안은 못 본다
    static final long SPACE_ADMIN = 3L;
    static final long GLOBAL_ADMIN = 4L;
    static final long OUTSIDER = 5L;     // 스페이스 권한 없음

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired NotificationRepository notifications;
    @Autowired FakePermissionClient perms;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    MockMvc mvc;
    Space space;
    Space other;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        notifications.deleteAll();
        jdbc.update("delete from space_watch");
        jdbc.update("delete from page_watch");
        jdbc.update("delete from page_revision");
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        space = spaces.save(Space.of("feed" + (System.nanoTime() % 100000), "피드", null, AUTHOR));
        other = spaces.save(Space.of("feedo" + (System.nanoTime() % 100000), "다른", null, AUTHOR));
        for (long u : new long[] {AUTHOR, READER}) {
            perms.allow(u, space.getId(), WikiAction.VIEW);
            perms.allow(u, space.getId(), WikiAction.EDIT);
        }
        perms.allow(AUTHOR, other.getId(), WikiAction.VIEW);
        perms.allow(AUTHOR, other.getId(), WikiAction.EDIT);
        perms.allow(SPACE_ADMIN, space.getId(), WikiAction.VIEW);
        perms.allow(SPACE_ADMIN, space.getId(), WikiAction.ADMIN);
        perms.allow(GLOBAL_ADMIN, space.getId(), WikiAction.VIEW);
        perms.allowGlobalAdmin(GLOBAL_ADMIN);
    }

    // ── 사람별 최근 문서 ──

    @Test
    void 사람별_문서는_만들었거나_마지막으로_고친_문서를_최근_수정순으로_준다() throws Exception {
        long mine = create(AUTHOR, space, null, "내가 쓴 문서", "published");
        long edited = create(AUTHOR, space, null, "남이 고친 문서", "published");
        long readers = create(READER, space, null, "남이 쓴 문서", "published");
        create(AUTHOR, space, null, "폴더", "published", "folder");
        update(READER, edited, "남이 고친 문서", "고쳤다", 1);
        touch(mine, Instant.now().minus(Duration.ofDays(2)));

        person(READER, AUTHOR, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].page.id").value(contains((int) edited, (int) mine)))
                .andExpect(jsonPath("$[0].page.title").value("남이 고친 문서"))
                .andExpect(jsonPath("$[0].page.status").value("published"))
                .andExpect(jsonPath("$[0].page.type").value("page"))
                .andExpect(jsonPath("$[0].page.updatedAt", notNullValue()))
                .andExpect(jsonPath("$[0].spaceId").value(String.valueOf(space.getId())))
                .andExpect(jsonPath("$[0].spaceName").value("피드"));
        // 마지막으로 고친 사람에게도 잡힌다
        person(AUTHOR, READER, null)
                .andExpect(jsonPath("$[*].page.id").value(containsInAnyOrder((int) edited, (int) readers)));
    }

    @Test
    void 사람별_문서에서_남의_초안과_그_하위는_작성자와_관리자에게만_보인다() throws Exception {
        long published = create(AUTHOR, space, null, "게시 문서", "published");
        long draft = create(AUTHOR, space, null, "초안", "draft");
        long under = create(AUTHOR, space, draft, "초안 아래", "published");

        person(READER, AUTHOR, null).andExpect(jsonPath("$[*].page.id").value(contains((int) published)));
        for (long viewer : new long[] {AUTHOR, SPACE_ADMIN, GLOBAL_ADMIN}) {
            person(viewer, AUTHOR, null).andExpect(jsonPath("$[*].page.id")
                    .value(containsInAnyOrder((int) published, (int) draft, (int) under)));
        }
        person(AUTHOR, AUTHOR, null).andExpect(jsonPath("$[?(@.page.id == " + draft + ")].page.status")
                .value(contains("draft")));
    }

    @Test
    void 사람별_문서는_스페이스_권한이_없으면_보이지_않는다() throws Exception {
        long inShared = create(AUTHOR, space, null, "같이 보는 문서", "published");
        create(AUTHOR, other, null, "다른 스페이스 문서", "published");

        person(OUTSIDER, AUTHOR, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        person(READER, AUTHOR, null).andExpect(jsonPath("$[*].page.id").value(contains((int) inShared)));
        person(AUTHOR, AUTHOR, null).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void 사람별_문서는_보관과_휴지통을_뺀다() throws Exception {
        long kept = create(AUTHOR, space, null, "남는 문서", "published");
        long archived = create(AUTHOR, space, null, "보관 문서", "published");
        long trashed = create(AUTHOR, space, null, "지운 문서", "published");
        mvc.perform(post("/api/wiki/pages/{id}/archive", archived).with(asUser(AUTHOR, "a"))).andExpect(status().isOk());
        mvc.perform(delete("/api/wiki/pages/{id}", trashed).with(asUser(AUTHOR, "a"))).andExpect(status().isNoContent());

        person(AUTHOR, AUTHOR, null).andExpect(jsonPath("$[*].page.id").value(contains((int) kept)));
    }

    @Test
    void 사람별_문서는_기본_20건_최대_50건이다() throws Exception {
        for (int i = 0; i < 55; i++) create(AUTHOR, space, null, "문서 " + i, "published");

        person(READER, AUTHOR, null).andExpect(jsonPath("$.length()").value(20));
        person(READER, AUTHOR, "1000").andExpect(jsonPath("$.length()").value(50));
        person(READER, AUTHOR, "0").andExpect(jsonPath("$.length()").value(1));
    }

    // ── 인기 ──

    @Test
    void 인기는_조회수_내림차순이고_동률은_최근_수정순이다() throws Exception {
        long low = create(AUTHOR, space, null, "조회 적음", "published");
        long tieOld = create(AUTHOR, space, null, "동률 옛", "published");
        long tieNew = create(AUTHOR, space, null, "동률 새", "published");
        long draft = create(READER, space, null, "많이 본 초안", "draft");
        long stale = create(AUTHOR, space, null, "오래된 인기", "published");
        long folder = create(AUTHOR, space, null, "폴더", "published", "folder");
        Instant now = Instant.now();
        views(low, 3, now.minus(Duration.ofDays(1)));
        views(tieOld, 10, now.minus(Duration.ofDays(5)));
        views(tieNew, 10, now.minus(Duration.ofDays(2)));
        views(draft, 99, now);
        views(stale, 1000, now.minus(Duration.ofDays(200))); // 기간 창 밖
        views(folder, 500, now);

        popular(READER, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].page.id").value(contains((int) tieNew, (int) tieOld, (int) low)))
                .andExpect(jsonPath("$[0].viewCount").value(10))
                .andExpect(jsonPath("$[0].spaceId").value(String.valueOf(space.getId())))
                .andExpect(jsonPath("$[0].spaceName").value("피드"))
                .andExpect(jsonPath("$[0].page.updatedAt", notNullValue()));
        // 초안은 그 작성자에게도 인기에 오르지 않는다(게시 문서만)
        popular(READER, null).andExpect(jsonPath("$.length()").value(3));
        popular(OUTSIDER, null).andExpect(jsonPath("$.length()").value(0));
        popular(READER, "2").andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void 인기는_볼_수_없는_문서가_앞에_몰려도_limit만큼_채운다() throws Exception {
        long draft = create(AUTHOR, space, null, "숨은 초안", "draft");
        Instant now = Instant.now();
        for (int i = 0; i < 3; i++) {
            views(create(AUTHOR, space, draft, "초안 아래 인기 " + i, "published"), 100 + i, now);
        }
        long a = create(AUTHOR, space, null, "보이는 1", "published");
        long b = create(AUTHOR, space, null, "보이는 2", "published");
        views(a, 5, now);
        views(b, 4, now);

        popular(READER, "2").andExpect(jsonPath("$[*].page.id").value(contains((int) a, (int) b)));
        popular(AUTHOR, "2").andExpect(jsonPath("$[0].viewCount").value(102));
    }

    @Test
    void 피드는_org_불능을_빈_목록으로_삼키지_않고_503을_준다() throws Exception {
        create(AUTHOR, space, null, "남의 초안", "draft"); // 남의 초안을 만나면 관리자 여부를 org에 묻는다
        create(AUTHOR, space, null, "게시", "published");
        perms.makeUnavailable(READER);

        person(READER, AUTHOR, null).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("권한 서비스에 연결할 수 없습니다"));
    }

    // ── 팔로잉 ──

    @Test
    void 팔로잉은_구독한_스페이스와_문서의_게시_문서를_최근_수정순으로_준다() throws Exception {
        perms.allow(READER, other.getId(), WikiAction.VIEW);
        long inWatched = create(AUTHOR, space, null, "구독 스페이스 문서", "published");
        create(AUTHOR, space, null, "구독 스페이스 초안", "draft");
        long watchedPage = create(AUTHOR, other, null, "구독한 문서", "published");
        create(AUTHOR, other, null, "구독 안 한 문서", "published");
        touch(inWatched, Instant.now().minus(Duration.ofDays(1)));

        mvc.perform(put("/api/wiki/spaces/{id}/watch", space.getId()).with(asUser(READER, "r")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/wiki/pages/{id}/watch", watchedPage).with(asUser(READER, "r")))
                .andExpect(status().is2xxSuccessful());

        following(READER).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].page.id").value(contains((int) watchedPage, (int) inWatched)))
                .andExpect(jsonPath("$[0].spaceName").value("다른"));

        // 구독은 남아 있어도 스페이스 권한을 잃으면 빠진다
        perms.reset();
        perms.allow(READER, space.getId(), WikiAction.VIEW);
        following(READER).andExpect(jsonPath("$[*].page.id").value(contains((int) inWatched)));
        following(OUTSIDER).andExpect(jsonPath("$.length()").value(0));
    }

    // ── 도우미 ──

    private ResultActions person(long caller, long person, String limit) throws Exception {
        MockHttpServletRequestBuilder req = get("/api/wiki/users/{id}/pages", person).with(asUser(caller, "u" + caller));
        if (limit != null) req = req.param("limit", limit);
        return mvc.perform(req);
    }

    private ResultActions popular(long caller, String limit) throws Exception {
        MockHttpServletRequestBuilder req = get("/api/wiki/feed/popular").with(asUser(caller, "u" + caller));
        if (limit != null) req = req.param("limit", limit);
        return mvc.perform(req);
    }

    private ResultActions following(long caller) throws Exception {
        return mvc.perform(get("/api/wiki/feed/following").with(asUser(caller, "u" + caller)));
    }

    private void views(long pageId, long count, Instant updatedAt) {
        jdbc.update("update page set view_count = ?, updated_at = ? where id = ?",
                count, Timestamp.from(updatedAt), pageId);
    }

    private void touch(long pageId, Instant updatedAt) {
        jdbc.update("update page set updated_at = ? where id = ?", Timestamp.from(updatedAt), pageId);
    }

    private long create(long userId, Space in, Long parentId, String title, String status) throws Exception {
        return create(userId, in, parentId, title, status, "page");
    }

    private long create(long userId, Space in, Long parentId, String title, String status, String type)
            throws Exception {
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(userId, "u" + userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + in.getId() + ",\"parentId\":" + parentId
                                + ",\"title\":\"" + title + "\",\"content\":\"본문\",\"status\":\"" + status
                                + "\",\"type\":\"" + type + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    private void update(long userId, long pageId, String title, String content, int expectedVersion)
            throws Exception {
        mvc.perform(put("/api/wiki/pages/{id}", pageId).with(asUser(userId, "u" + userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"content\":\"" + content
                                + "\",\"expectedVersion\":" + expectedVersion + "}"))
                .andExpect(status().isOk());
    }
}
