package com.platform.wikibackend.permission;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 전역 관리자 판정(2026-09-12) — org {@code CheckPermission(GLOBAL, ADMIN)}으로 옮긴 경로를 지킨다.
 *
 * <p>전역 관리자만 들어가는 경로는 둘이다: 관리자 현황(`/api/wiki/admin/stats`)과 스페이스 삭제
 * 기록(`/api/wiki/audit/space-deletions`). 넷을 본다 — 판정이 GLOBAL 요청으로 가는가, grant 목록으로
 * 되돌아가지 않았는가, 계정 상태 사유가 403 문구에 실리는가, org 불능이 403이 아니라 503인가.
 */
@SpringBootTest
@ActiveProfiles("test")
class GlobalAdminApiTest {

    private static final String STATS = "/api/wiki/admin/stats";
    private static final String DELETIONS = "/api/wiki/audit/space-deletions";

    private static final long GLOBAL_ADMIN = 41L;
    private static final long EVERY_SPACE = 42L;
    private static final long OUTSIDER = 43L;

    @Autowired WebApplicationContext context;
    @Autowired FakePermissionClient perms;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // 현황 캐시는 건드리지 않는다 — 이 테스트는 숫자가 아니라 판정만 본다(권한은 캐시 앞에서 돈다).
        perms.reset();
    }

    /** 판정이 실제로 GLOBAL·ADMIN을 묻는다 — 스페이스 grant를 하나도 주지 않았는데 통과한다. */
    @ParameterizedTest
    @ValueSource(strings = {STATS, DELETIONS})
    void 전역_판정은_GLOBAL_ADMIN_요청으로_간다(String path) throws Exception {
        perms.allowGlobalAdmin(GLOBAL_ADMIN);

        mvc.perform(get(path).with(asUser(GLOBAL_ADMIN, "전역")))
                .andExpect(status().isOk());

        assertThat(perms.globalChecks)
                .contains(new FakePermissionClient.GlobalCheck(GLOBAL_ADMIN, WikiAction.ADMIN));
    }

    /**
     * 전 스페이스가 보이는 사람(grant 목록의 {@code all()})이라도 전역 관리자가 아니면 막힌다.
     * 판정이 {@code accessibleSpaces().all()}로 되돌아가면 이 테스트가 200을 받아 깨진다.
     */
    @ParameterizedTest
    @CsvSource({
            STATS + ", 플랫폼 현황은 전역 관리자만 볼 수 있습니다",
            DELETIONS + ", 스페이스 삭제 기록은 전역 관리자만 볼 수 있습니다"})
    void 전_스페이스가_보여도_전역_관리자가_아니면_403이다(String path, String message) throws Exception {
        perms.allowAllSpaces(EVERY_SPACE);

        mvc.perform(get(path).with(asUser(EVERY_SPACE, "전체열람")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(message));
    }

    /** 사유 없는 거부는 경로마다 다른 기존 문구를 그대로 쓴다 — 무엇을 보려다 막혔는지 알려 준다. */
    @ParameterizedTest
    @CsvSource({
            STATS + ", 플랫폼 현황은 전역 관리자만 볼 수 있습니다",
            DELETIONS + ", 스페이스 삭제 기록은 전역 관리자만 볼 수 있습니다"})
    void 권한만_모자란_거부는_기존_문구다(String path, String message) throws Exception {
        perms.denyWithReason(OUTSIDER, "NO_GRANT");

        mvc.perform(get(path).with(asUser(OUTSIDER, "남")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(message));
    }

    /**
     * 계정 상태로 막힌 것이면 그 사실을 말한다 — 전역 관리자 문구를 덮는다. 문구는 alm-backend와
     * 같은 문자열이다(`PermissionDecision.accountMessage`). "권한이 없다"와 "승인 대기 중"은 사용자가
     * 할 조치가 다르다.
     */
    @ParameterizedTest
    @CsvSource({
            "PENDING, 승인 대기 중인 계정입니다",
            "SUSPENDED, 정지된 계정입니다",
            "DEACTIVATED, 비활성된 계정입니다"})
    void 계정_상태_사유가_403_문구에_실린다(String reason, String message) throws Exception {
        perms.denyWithReason(OUTSIDER, reason);

        mvc.perform(get(STATS).with(asUser(OUTSIDER, "대기")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(message));

        mvc.perform(get(DELETIONS).with(asUser(OUTSIDER, "대기")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(message));
    }

    /** 모르는 사유는 일반 거부다 — 값은 뒤에 늘 수 있다(proto 0.16.0 주석). */
    @Test
    void 모르는_사유는_기존_문구로_떨어진다() throws Exception {
        perms.denyWithReason(OUTSIDER, "SOME_FUTURE_REASON");

        mvc.perform(get(STATS).with(asUser(OUTSIDER, "남")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("플랫폼 현황은 전역 관리자만 볼 수 있습니다"));
    }

    /**
     * org 불능은 403이 아니라 503이다. 관리자에게 "당신은 관리자가 아닙니다"라고 답하면 사람이
     * 잘못된 조치를 한다 — 권한을 다시 달라고 요청하게 된다.
     */
    @ParameterizedTest
    @ValueSource(strings = {STATS, DELETIONS})
    void org_불능은_503이다(String path) throws Exception {
        perms.allowGlobalAdmin(GLOBAL_ADMIN);
        perms.makeUnavailable(GLOBAL_ADMIN);

        mvc.perform(get(path).with(asUser(GLOBAL_ADMIN, "전역")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("권한 서비스에 연결할 수 없습니다"));
    }
}
