package com.platform.wikibackend.migration.confluence.restriction;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 테스트 전용 페이크 — 원본 사용자·그룹을 우리 id로 대조하는 창구를 흉내 낸다.
 *
 * 운영 기본 구현({@link UnmappedMigrationPrincipalResolver})은 아무것도 대조하지 못한다(org에
 * 이름 조회 API가 없다). 그 상태만 테스트하면 fail-closed 경로만 보게 되므로, 여기서 "대조에
 * 성공했을 때"도 함께 고정한다 — 나중에 실제 구현이 들어와도 규칙이 그대로여야 한다.
 */
@Component
@Primary
public class FakeMigrationPrincipalResolver implements MigrationPrincipalResolver {

    private final Map<String, Long> users = new HashMap<>();
    private final Map<String, Long> teams = new HashMap<>();

    public void reset() {
        users.clear();
        teams.clear();
    }

    public void mapUser(String username, long userId) {
        users.put(username, userId);
    }

    public void mapTeam(String groupName, long teamId) {
        teams.put(groupName, teamId);
    }

    @Override
    public Optional<Long> resolveUser(SourceUser user) {
        return Optional.ofNullable(users.get(user.username()));
    }

    @Override
    public Optional<Long> resolveTeam(String groupName) {
        return Optional.ofNullable(teams.get(groupName));
    }
}
