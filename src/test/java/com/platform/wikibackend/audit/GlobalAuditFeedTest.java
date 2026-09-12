package com.platform.wikibackend.audit;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.AuditAction;
import com.platform.wikibackend.domain.AuditLog;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.AuditLogRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 전역 감사 피드 — 관리자 대시보드의 "최근 활동"이 읽는다.
 *
 * 스페이스 스코프 조회(스페이스 ADMIN)와 판정 기준이 다르다: 전 스페이스를 가로지르는 목록은
 * 전역 관리자만 본다(스페이스 삭제 기록·관리자 현황과 같은 기준). 응답 필드 이름은 프론트
 * 어댑터와 맞춘 계약이라 엔티티 컬럼명과 다르다 — 그 매핑이 어긋나면 화면이 빈다.
 */
@SpringBootTest
@ActiveProfiles("test")
class GlobalAuditFeedTest {

    private static final long GLOBAL = 9L;
    private static final long OTHER = 2L;
    private static final long ACTOR = 7L;

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired AuditLogRepository logs;
    @Autowired FakePermissionClient perms;
    @Autowired JdbcTemplate jdbc;

    MockMvc mvc;
    Space space;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        logs.deleteAllInBatch();
        TestPages.deleteAll(jdbc);
        spaces.deleteAllInBatch();
        perms.reset();
        perms.allowAll(GLOBAL);
        space = spaces.save(Space.of("docs", "설계", null, ACTOR));
    }

    /** 감사 행을 직접 심는다 — 조작을 거치면 시각을 통제할 수 없어 since·정렬을 검증할 수 없다. */
    private AuditLog record(AuditAction action, String targetType, Long targetId, String label, String detail) {
        return logs.save(AuditLog.of(space.getId(), ACTOR, action, targetType, targetId, label, detail));
    }

    /** @CreationTimestamp가 박은 시각을 뒤로 민다. */
    private void backdate(AuditLog log, Instant when) {
        jdbc.update("update audit_log set created_at = ? where id = ?",
                OffsetDateTime.ofInstant(when, ZoneOffset.UTC), log.getId());
    }

    @Test
    void 전역_관리자가_아니면_403() throws Exception {
        mvc.perform(get("/api/wiki/audit").with(asUser(OTHER, "남")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
    }

    /** 전 스페이스가 보인다는 것(목록 필터)은 전역 관리자라는 뜻이 아니다 — 판정이 되돌아가면 여기서 들킨다. */
    @Test
    void 전_스페이스가_보여도_전역_관리자가_아니면_403() throws Exception {
        perms.allowAllSpaces(OTHER);

        mvc.perform(get("/api/wiki/audit").with(asUser(OTHER, "남")))
                .andExpect(status().isForbidden());
        // grant 목록이 아니라 GLOBAL 판정을 실제로 탔는지까지 본다(결과만 같은 우회를 막는다).
        assertThat(perms.globalChecks)
                .containsExactly(new FakePermissionClient.GlobalCheck(OTHER, WikiAction.ADMIN));
    }

    /** org가 죽은 동안 "당신은 관리자가 아닙니다"라고 답하면 사람이 잘못된 조치를 한다. */
    @Test
    void org가_불능이면_503이다() throws Exception {
        perms.makeUnavailable(GLOBAL);

        mvc.perform(get("/api/wiki/audit").with(asUser(GLOBAL, "전역")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
    }

    /** 계약 shape — 프론트 어댑터가 이 필드 이름으로 읽는다. */
    @Test
    void 계약대로_필드를_매핑한다() throws Exception {
        record(AuditAction.PAGE_TRASHED, "PAGE", 55L, "설계 문서", null);

        mvc.perform(get("/api/wiki/audit").with(asUser(GLOBAL, "전역")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").isNumber())
                .andExpect(jsonPath("$.items[0].eventType").value("PAGE_TRASHED"))
                .andExpect(jsonPath("$.items[0].actorId").value(ACTOR))
                .andExpect(jsonPath("$.items[0].spaceId").value(space.getId()))
                .andExpect(jsonPath("$.items[0].spaceKey").value("docs"))
                .andExpect(jsonPath("$.items[0].pageId").value(55))
                .andExpect(jsonPath("$.items[0].targetTitle").value("설계 문서"))
                .andExpect(jsonPath("$.items[0].summary").value("페이지 휴지통 이동"))
                .andExpect(jsonPath("$.items[0].occurredAt").isString());
    }

    /** 페이지가 아닌 대상은 pageId가 없다 — 프론트가 링크를 만들지 않는 신호다. */
    @Test
    void 페이지가_아닌_대상은_pageId가_null이다() throws Exception {
        record(AuditAction.SPACE_UPDATED, "SPACE", space.getId(), "설계", "이름 변경: 설계도");

        mvc.perform(get("/api/wiki/audit").with(asUser(GLOBAL, "전역")))
                .andExpect(jsonPath("$.items[0].pageId").value(nullValue()))
                .andExpect(jsonPath("$.items[0].summary").value("스페이스 정보 변경 — 이름 변경: 설계도"));
    }

    /** 기록은 스페이스보다 오래 산다(V30) — 그 자리는 비지만 나머지는 읽혀야 한다. */
    @Test
    void 지워진_스페이스의_기록은_spaceKey가_null이다() throws Exception {
        logs.save(AuditLog.of(9999L, ACTOR, AuditAction.SPACE_DELETED, "SPACE", 9999L, "옛 스페이스 (old)", null));

        mvc.perform(get("/api/wiki/audit").with(asUser(GLOBAL, "전역")))
                .andExpect(jsonPath("$.items[0].spaceId").value(9999))
                .andExpect(jsonPath("$.items[0].spaceKey").value(nullValue()))
                .andExpect(jsonPath("$.items[0].targetTitle").value("옛 스페이스 (old)"));
    }

    @Test
    void 최신이_먼저_오고_페이지로_나뉜다() throws Exception {
        record(AuditAction.PAGE_TRASHED, "PAGE", 1L, "첫째", null);
        record(AuditAction.PAGE_TRASHED, "PAGE", 2L, "둘째", null);
        record(AuditAction.PAGE_TRASHED, "PAGE", 3L, "셋째", null);

        mvc.perform(get("/api/wiki/audit").param("page", "0").param("size", "2")
                        .with(asUser(GLOBAL, "전역")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].targetTitle").value("셋째"))
                .andExpect(jsonPath("$.items[1].targetTitle").value("둘째"));

        mvc.perform(get("/api/wiki/audit").param("page", "1").param("size", "2")
                        .with(asUser(GLOBAL, "전역")))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].targetTitle").value("첫째"));
    }

    @Test
    void type으로_거른다() throws Exception {
        record(AuditAction.PAGE_TRASHED, "PAGE", 1L, "지운 문서", null);
        record(AuditAction.TEMPLATE_CREATED, "TEMPLATE", 2L, "회의록", null);

        mvc.perform(get("/api/wiki/audit").param("type", "TEMPLATE_CREATED")
                        .with(asUser(GLOBAL, "전역")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].eventType").value("TEMPLATE_CREATED"));
    }

    /** 오타를 조용히 "기록 없음"으로 돌려주면 화면에서 구별할 방법이 없다. */
    @Test
    void 모르는_type은_400이다() throws Exception {
        mvc.perform(get("/api/wiki/audit").param("type", "PAGE_UPDATED").with(asUser(GLOBAL, "전역")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void since_이전_기록은_빠진다() throws Exception {
        AuditLog old = record(AuditAction.PAGE_TRASHED, "PAGE", 1L, "오래된", null);
        backdate(old, Instant.now().minus(10, ChronoUnit.DAYS));
        record(AuditAction.PAGE_TRASHED, "PAGE", 2L, "최근", null);

        String since = Instant.now().minus(1, ChronoUnit.DAYS).toString();
        mvc.perform(get("/api/wiki/audit").param("since", since).with(asUser(GLOBAL, "전역")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].targetTitle").value("최근"));
    }

    /** 상한을 넘겨도 거절하지 않고 자른다 — 응답의 size가 실제 적용값이다. */
    @Test
    void size는_100을_넘지_않는다() throws Exception {
        record(AuditAction.PAGE_TRASHED, "PAGE", 1L, "하나", null);

        mvc.perform(get("/api/wiki/audit").param("size", "5000").with(asUser(GLOBAL, "전역")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(AuditService.FEED_MAX_SIZE))
                .andExpect(jsonPath("$.items", hasSize(1)));
    }

    /** 음수 페이지로 PageRequest가 터지지 않는다(0으로 본다). */
    @Test
    void 음수_페이지는_첫_페이지로_본다() throws Exception {
        record(AuditAction.PAGE_TRASHED, "PAGE", 1L, "하나", null);

        mvc.perform(get("/api/wiki/audit").param("page", "-3").with(asUser(GLOBAL, "전역")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.items", hasSize(1)));
    }
}
