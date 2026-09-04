package com.platform.wikibackend.permission;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 테스트용 팀 멤버십 — FakePermissionClient와 같은 이유로 @Primary(빈 순서 무관 강제 대체). */
@Component
@Primary
@org.springframework.context.annotation.Profile("!docs")   // docs 프로필은 프로필 전용 빈을 그대로 검증한다
public class FakeTeamDirectory implements TeamDirectory {

    private final Map<Long, List<Long>> byUser = new HashMap<>();

    public void reset() {
        byUser.clear();
    }

    public void join(long userId, long teamId) {
        byUser.computeIfAbsent(userId, k -> new ArrayList<>()).add(teamId);
    }

    @Override
    public List<Long> teamsOf(long userId) {
        return byUser.getOrDefault(userId, List.of());
    }
}
