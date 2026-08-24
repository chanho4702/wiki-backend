package com.platform.wikibackend.permission;

import java.util.List;

/**
 * 사용자의 팀 멤버십 조회 — 원장은 org-service(team_member). 페이지 제한의 TEAM 주체 판정에 쓴다.
 * W18 증분 2에서 org gRPC(ListTeamsOf) 구현으로 교체한다 — 그 전까지 기본 구현은 빈 목록
 * (TEAM 제한에 대해 fail-closed: 팀 멤버십을 모르면 통과시키지 않는다, 인가 안전 우선 §2 원칙).
 */
public interface TeamDirectory {
    List<Long> teamsOf(long userId);
}
