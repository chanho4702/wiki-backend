package com.platform.wikibackend.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.platform.wikibackend.space.SpaceController.userId;

/** 스페이스 감사 로그(W23) — 스페이스 ADMIN만. */
@RestController
@RequiredArgsConstructor
public class AuditController {

    private final AuditService audit;

    @GetMapping("/api/wiki/spaces/{spaceId}/audit")
    public List<AuditService.AuditEntry> list(@AuthenticationPrincipal Jwt jwt,
                                              @PathVariable Long spaceId) {
        return audit.list(userId(jwt), spaceId);
    }
}
