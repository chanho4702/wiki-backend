package com.platform.wikibackend.collaboration;

import java.time.Instant;

/** 브라우저에는 짧은 원문 ticket만 돌려준다. Access Token은 WebSocket URL로 전달하지 않는다. */
public record CollaborationTicketResponse(
        String ticket,
        String room,
        String websocketPath,
        Instant expiresAt) {
}
