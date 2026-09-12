package com.platform.wikibackend.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;

import static com.platform.wikibackend.space.SpaceController.userId;

/** 감사 로그(W23) — 스페이스 스코프는 스페이스 ADMIN, 전역 경로는 전역 관리자만. */
@Tag(name = "Audit", description = "감사 로그 조회 — 스페이스 스코프와 전역 피드.")
@RestController
@RequiredArgsConstructor
public class AuditController {

    private final AuditService audit;

    /**
     * 전역 감사 피드 — 전역 관리자만. 관리자 대시보드의 "최근 활동"이 읽는다.
     *
     * 스페이스 스코프 조회와 달리 페이지네이션과 필터가 있다. 전 스페이스를 가로지르는 목록은
     * 상위 100건 고정으로는 어제 일도 못 본다.
     */
    @Operation(summary = "전역 감사 피드를 조회한다 — 전역 관리자만")
    @GetMapping("/api/wiki/audit")
    public AuditService.AuditFeedPage feed(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "0부터 세는 페이지 번호. 음수면 0으로 본다")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기. 기본 20, 상한 100 — 넘겨도 거절하지 않고 100으로 자른다")
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "eventType 필터(선택). 모르는 값이면 400")
            @RequestParam(required = false) String type,
            @Parameter(description = "이 시각 이후만(선택, ISO-8601 — 예: 2026-09-01T00:00:00Z)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        return audit.feed(userId(jwt), page, size, type, since);
    }

    /** 스페이스 삭제 기록 — 전역 관리자만. 스페이스 안에서는 읽을 곳이 없어 전역 경로다. */
    @Operation(summary = "스페이스 삭제 기록을 조회한다 — 전역 관리자만")
    @GetMapping("/api/wiki/audit/space-deletions")
    public List<AuditService.AuditEntry> spaceDeletions(@AuthenticationPrincipal Jwt jwt) {
        return audit.listSpaceDeletions(userId(jwt));
    }

    @Operation(summary = "스페이스의 감사 로그를 조회한다 — 스페이스 ADMIN만")
    @GetMapping("/api/wiki/spaces/{spaceId}/audit")
    public List<AuditService.AuditEntry> list(@AuthenticationPrincipal Jwt jwt,
                                              @Parameter(description = "스페이스 ID") @PathVariable Long spaceId) {
        return audit.list(userId(jwt), spaceId);
    }
}
