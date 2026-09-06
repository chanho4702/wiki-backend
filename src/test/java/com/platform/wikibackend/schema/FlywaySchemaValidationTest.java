package com.platform.wikibackend.schema;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageComment;
import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.domain.PageType;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.config.InternalTokenFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.wikibackend.repository.PageCommentRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Flyway 마이그레이션 ↔ JPA 엔티티 정합을 실제 Postgres로 검증한다.
 *
 * 나머지 테스트는 H2 + `ddl-auto: create-drop`이라 **마이그레이션을 아예 타지 않는다** —
 * 엔티티에 필드를 추가하고 `V*.sql`을 빠뜨려도 전부 통과하고, 운영에서 `ddl-auto: validate`가
 * 부팅을 거부한다. 그 간극을 이 테스트가 메운다.
 *
 * 여기서 하는 일:
 * 1. 빈 Postgres에 Flyway로 V1→현재 버전을 적용하고
 * 2. `ddl-auto: validate`로 컨텍스트를 띄운다(불일치면 컨텍스트 로딩 실패 = 테스트 실패)
 * 3. 실제 INSERT/SELECT로 H2에는 없는 제약(FK·CHECK)까지 살아 있는지 본다.
 *
 * Docker가 필요하다. 없으면 이 테스트만 실패하므로, 로컬에서 Docker 없이 돌릴 땐
 * `--tests '*' -x` 대신 `./gradlew test --tests '!*FlywaySchemaValidationTest'`로 제외한다.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none",
        // V38 부분 유니크 인덱스는 실제 Postgres에만 있다 — 멱등 경합 수렴을 여기서만 확인할 수 있다.
        "platform.wiki.internal-token=test-internal-token",
})
@ActiveProfiles("test")
@Testcontainers
class FlywaySchemaValidationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageCommentRepository comments;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;

    /**
     * 컨텍스트가 떴다는 것 자체가 "마이그레이션 결과 스키마 == 엔티티 매핑"의 증거다
     * (validate가 컬럼 누락·타입 불일치에서 부팅을 거부한다).
     */
    @Test
    void 마이그레이션_스키마로_엔티티_검증이_통과한다() {
        assertThat(postgres.isRunning()).isTrue();
    }

    @Test
    void V2가_추가한_type_status가_실제로_저장되고_읽힌다() {
        Space s = spaces.save(Space.of("dev", "개발", null, 1L));
        Page folder = pages.save(Page.of(s.getId(), null, "폴더", "", 1L, PageType.FOLDER, PageStatus.PUBLISHED));
        Page draft = pages.save(Page.of(s.getId(), null, "초안", "", 1L, PageType.PAGE, PageStatus.DRAFT));

        assertThat(pages.findById(folder.getId()).orElseThrow().getType()).isEqualTo(PageType.FOLDER);
        assertThat(pages.findById(draft.getId()).orElseThrow().getStatus()).isEqualTo(PageStatus.DRAFT);
    }

    /**
     * H2 테스트 스키마엔 FK가 아예 없다(Wave B에서 세 번 물린 지점) — 운영 Postgres에는
     * `page.parent_id → page.id ON DELETE CASCADE`가 실제로 걸려 있는지 확인한다.
     */
    @Test
    void 부모_페이지를_지우면_DB_cascade로_자식도_사라진다() {
        Space s = spaces.save(Space.of("ops", "운영", null, 1L));
        Page parent = pages.save(Page.of(s.getId(), null, "부모", "", 1L));
        Page child = pages.save(Page.of(s.getId(), parent.getId(), "자식", "", 1L));

        pages.deleteById(parent.getId());
        pages.flush();

        assertThat(pages.findById(child.getId())).isEmpty();
    }

    /** V8 댓글 — page cascade와 답글 cascade는 실제 Postgres FK에서만 확인된다. */
    @Test
    void V8_댓글은_페이지와_최상위_댓글_삭제를_cascade로_따라간다() {
        Space space = spaces.save(Space.of("cmt", "댓글", null, 1L));
        Page page = pages.save(Page.of(space.getId(), null, "본문", "", 1L));
        PageComment parent = comments.saveAndFlush(PageComment.of(page.getId(), null, 1L, "Alice", "최상위"));
        comments.saveAndFlush(PageComment.of(page.getId(), parent.getId(), 2L, "Bob", "답글"));

        pages.deleteById(page.getId());
        pages.flush();

        assertThat(comments.count()).isZero();
    }


    /**
     * V37 — 이관 엔진이 migration-service로 나가면서 잡 원장 테이블이 사라졌다(W29 X4).
     *
     * H2는 마이그레이션을 타지 않으므로 DROP이 실제로 도는 곳은 여기뿐이다. 남아 있으면 위키가
     * 갱신하지 않는 원장이 조용히 늙는다 — 엔진 쪽 원장과 갈라진 사본이 가장 나쁜 상태다.
     */
    @Test
    void V37이_이관_잡_원장_테이블을_전부_지웠다() {
        for (String table : new String[] {"migration_job", "migration_item", "migration_issue",
                "migration_object_map", "migration_source", "migration_payload"}) {
            assertThat(exists(table)).as(table).isFalse();
        }
    }

    /**
     * 반대로 page의 이관 표시 컬럼(V36)은 남아야 한다 — 잡 원장이 아니라 문서 자신의 속성이고,
     * import API가 지금도 채운다.
     */
    @Test
    void 이관_작성자_표시_컬럼은_남아_있다() {
        Space space = spaces.save(Space.of("kept", "유지", null, 1L));
        Page page = pages.save(Page.imported(space.getId(), null, "옮겨온 문서", "본문", 1L,
                Instant.parse("2020-01-02T03:04:05Z"), Instant.parse("2021-01-02T03:04:05Z")));
        page.markImportedAuthor("Jane Confluence", "https://dc.example.com/x");
        pages.saveAndFlush(page);

        assertThat(pages.findById(page.getId()).orElseThrow().getImportedAuthorName())
                .isEqualTo("Jane Confluence");
    }

    private boolean exists(String table) {
        Boolean found = jdbc.queryForObject(
                "select exists (select 1 from information_schema.tables"
                        + " where table_schema = 'public' and table_name = ?)",
                Boolean.class, table);
        return Boolean.TRUE.equals(found);
    }

    // ── V38 이관 멱등 키 ──

    /**
     * 부분 유니크 인덱스는 H2 스키마(create-drop)에 없다 — 같은 원본이 문서 두 벌이 되는 것을
     * 실제로 막는지는 여기서만 확인된다. 이것이 최종 방어선이고, 서비스의 사전 조회는 그 앞의
     * 편의다(경합에서는 조회가 늦는다).
     */
    @Test
    void V38은_같은_importKey의_살아_있는_문서를_둘로_두지_않는다() {
        Space space = spaces.save(Space.of("idem", "멱등", null, 1L));
        pages.saveAndFlush(imported(space, "첫 이관", "confluence-dc:wiki:1"));

        assertThatThrownBy(() -> pages.saveAndFlush(imported(space, "같은 원본", "confluence-dc:wiki:1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * 버린 문서의 키는 풀린다. 인덱스가 휴지통 행까지 잡으면 조회 경로(@SQLRestriction)에는
     * 안 보이는데 INSERT만 거부되는, 코드로 빠져나갈 수 없는 상태가 된다.
     */
    @Test
    void 휴지통으로_간_문서의_importKey는_다시_쓸_수_있다() {
        Space space = spaces.save(Space.of("idem2", "멱등2", null, 1L));
        Page first = pages.saveAndFlush(imported(space, "첫 이관", "confluence-dc:wiki:2"));
        jdbc.update("update page set deleted_at = now(), deleted_by = 1, deleted_root = true where id = ?",
                first.getId());

        Page again = pages.saveAndFlush(imported(space, "다시 이관", "confluence-dc:wiki:2"));

        assertThat(again.getId()).isNotEqualTo(first.getId());
    }

    /**
     * 같은 키로 동시에 들어와도 문서는 하나로 수렴한다. 사전 조회가 둘 다 "없다"를 보면 한쪽이
     * 유니크 위반을 맞는데, 그것을 오류로 올리면 엔진은 실패로 보고 또 재시도한다 — 이미 들어가
     * 있는데도. 서비스가 위반을 잡아 다시 찾아보고 EXISTING으로 답하는지 확인한다.
     *
     * 두 스레드가 실제로 겹치지 않으면 두 번째는 사전 조회에서 EXISTING이 된다 — 어느 경로로
     * 가든 결론(문서 1건, 같은 pageId)은 같아야 한다는 것이 이 테스트의 주장이다.
     */
    @Test
    void 같은_importKey_동시_요청은_문서_하나로_수렴한다() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Space space = spaces.save(Space.of("race", "경합", null, 1L));
        String body = json.createObjectNode()
                .put("spaceId", space.getId())
                .put("importKey", "confluence-dc:wiki:race")
                .put("title", "동시 이관")
                .put("content", "본문")
                .put("createdAt", "2020-01-02T03:04:05Z")
                .put("updatedAt", "2021-01-02T03:04:05Z")
                .toString();

        CountDownLatch start = new CountDownLatch(1);
        List<String> responses = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    responses.add(mvc.perform(post("/internal/wiki/import/pages")
                                    .header(InternalTokenFilter.TOKEN_HEADER, "test-internal-token")
                                    .header(InternalTokenFilter.ACTOR_HEADER, "1")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andReturn().getResponse().getContentAsString());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(30_000);
        }

        assertThat(responses).hasSize(2);
        List<Long> ids = new ArrayList<>();
        for (String response : responses) {
            JsonNode node = json.readTree(response);
            assertThat(node.has("error")).as(response).isFalse();
            ids.add(node.path("pageId").asLong());
        }
        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(pages.findBySpaceIdAndTitleIgnoringCase(space.getId(), "동시 이관")).hasSize(1);
    }

    private static Page imported(Space space, String title, String importKey) {
        Page page = Page.imported(space.getId(), null, title, "본문", 1L,
                Instant.parse("2020-01-02T03:04:05Z"), Instant.parse("2021-01-02T03:04:05Z"));
        page.markImportKey(importKey);
        return page;
    }
}
