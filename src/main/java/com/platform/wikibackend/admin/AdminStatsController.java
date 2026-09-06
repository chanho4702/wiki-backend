package com.platform.wikibackend.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.platform.wikibackend.space.SpaceController.userId;

/** 플랫폼 관리자 대시보드가 읽는 위키 현황(설계 §4.1) — 전역 관리자만. */
@Tag(name = "Admin", description = "플랫폼 관리자 전용 위키 현황. 전역 관리자만 읽을 수 있다.")
@RestController
@RequiredArgsConstructor
public class AdminStatsController {

    private final AdminStatsService adminStats;

    @Operation(summary = "위키 전체 현황 통계를 조회한다 — 전역 관리자만")
    @GetMapping("/api/wiki/admin/stats")
    public WikiAdminStats stats(@AuthenticationPrincipal Jwt jwt) {
        return adminStats.stats(userId(jwt));
    }
}
