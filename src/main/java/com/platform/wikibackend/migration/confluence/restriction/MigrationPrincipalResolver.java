package com.platform.wikibackend.migration.confluence.restriction;

import java.util.Optional;

/**
 * 원본 컨플루언스의 사용자·그룹을 우리 계정·팀으로 대조한다.
 *
 * 원장은 org-service다. 지금 wiki가 org에 물을 수 있는 것은 "이 id가 실재하는가"
 * ({@code ValidatePrincipals})와 "이 사용자가 속한 팀"({@code ListUserTeams})뿐이고,
 * **이름·이메일로 사용자를 찾는 창구는 없다**(common-proto 0.14.0 확인). 그래서 기본 구현은
 * 아무것도 대조하지 못하고, 그 결과는 fail-closed로 이어진다(ADR-W14-07) — 못 찾은 주체를
 * 공개로 풀지 않고 잡 요청자 단독 제한으로 닫는다.
 *
 * org에 조회 API가 생기면 이 인터페이스의 구현만 갈아끼우면 된다. 제한 적용 규칙은 그대로다.
 */
public interface MigrationPrincipalResolver {

    /** 원본 사용자 → 우리 사용자 id. 못 찾으면 빈 값. */
    Optional<Long> resolveUser(SourceUser user);

    /** 원본 그룹 이름 → 우리 팀 id. 못 찾으면 빈 값. */
    Optional<Long> resolveTeam(String groupName);

    /** 원본이 알려주는 사람 식별자. 셋 다 비어 있을 수 있다. */
    record SourceUser(String username, String displayName, String email) {

        /** 보고서에 남길 이름 — 사람이 원본에서 찾아갈 수 있는 값을 고른다. */
        public String label() {
            if (username != null && !username.isBlank()) {
                return username;
            }
            if (email != null && !email.isBlank()) {
                return email;
            }
            return displayName == null || displayName.isBlank() ? "unknown" : displayName;
        }
    }
}
