package com.platform.wikibackend.collaboration;

import java.time.Instant;

/**
 * 브라우저에는 짧은 원문 ticket만 돌려준다. Access Token은 WebSocket URL로 전달하지 않는다.
 * {@code pageVersion}은 발급 시점 page 버전, {@code draftEpoch}는 발급 시점 공동 초안 reset_epoch(행 없으면 0)다 —
 * 프론트가 세션의 기준 버전·첫 티켓의 epoch와 달라졌는지 본다(버전 불변 리셋도 잡는다).
 */
public record CollaborationTicketResponse(
        String ticket,
        String room,
        String websocketPath,
        Instant expiresAt,
        long pageVersion,
        long draftEpoch) {
}
