package com.platform.wikibackend.collaboration;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Collaboration", description = "공동 편집 세션 접속용 1회용 티켓.")
@RestController
@RequiredArgsConstructor
public class CollaborationTicketController {

    private final CollaborationTicketService tickets;

    @Operation(summary = "공동 편집 WebSocket 접속에 쓸 1회용 티켓을 발급한다")
    @PostMapping("/api/wiki/pages/{pageId}/collaboration-ticket")
    public ResponseEntity<CollaborationTicketResponse> issue(
            @PathVariable long pageId,
            @AuthenticationPrincipal Jwt jwt) {
        long userId = Long.parseLong(jwt.getSubject());
        CollaborationTicketResponse ticket = tickets.issue(
                userId, jwt.getClaimAsString("name"), pageId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(ticket);
    }
}
