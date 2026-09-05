package com.platform.wikibackend.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import static com.platform.wikibackend.space.SpaceController.userId;

/** 스페이스 감사 로그(W23) — 스페이스 ADMIN만. */
@Tag(name = "Audit", description = "스페이스 감사 로그 조회.")
@RestController
@RequiredArgsConstructor
public class AuditController {

    private final AuditService audit;

    /** 스페이스 삭제 기록 — 전역 관리자만. 스페이스 안에서는 읽을 곳이 없어 전역 경로다. */
    @Operation(summary = "스페이스 삭제 기록을 조회한다 — 전역 관리자만")
    @GetMapping("/api/wiki/audit/space-deletions")
    public List<AuditService.AuditEntry> spaceDeletions(@AuthenticationPrincipal Jwt jwt) {
        return audit.listSpaceDeletions(userId(jwt));
    }

    @Operation(summary = "스페이스의 감사 로그를 조회한다 — 스페이스 ADMIN만")
    @GetMapping("/api/wiki/spaces/{spaceId}/audit")
    public List<AuditService.AuditEntry> list(@AuthenticationPrincipal Jwt jwt,
                                              @PathVariable Long spaceId) {
        return audit.list(userId(jwt), spaceId);
    }
}
