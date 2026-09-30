package com.platform.wikibackend.collaboration;

import java.time.Instant;

/**
 * Redis에 TTL로 저장되는 collaboration service 교차 런타임 계약(v2). 원문 ticket은 포함하지 않는다.
 *
 * v2는 발급 시점 page 버전({@code pageVersion})과 공동 초안 리셋 카운터({@code draftEpoch}, 행이 없으면 0)를
 * 더한다 — collaboration-service가 bootstrap 기준 버전·현재 reset_epoch와 대조해, 옛 페이지나 리셋 전 초안을
 * 든 클라이언트가 새 초안에 옛 Y.Doc을 합치지 못하게 한다.
 */
public record CollaborationTicketPayload(
        int schemaVersion,
        long pageId,
        long userId,
        String displayName,
        String room,
        String permission,
        long pageVersion,
        long draftEpoch,
        Instant issuedAt,
        Instant expiresAt) {

    public static final int SCHEMA_VERSION = 2;
    public static final String EDIT_PERMISSION = "EDIT";
}
