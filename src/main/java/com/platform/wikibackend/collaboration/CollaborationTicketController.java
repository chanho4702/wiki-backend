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

@RestController
@RequiredArgsConstructor
public class CollaborationTicketController {

    private final CollaborationTicketService tickets;

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
