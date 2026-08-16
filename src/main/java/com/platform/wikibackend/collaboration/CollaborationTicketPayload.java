package com.platform.wikibackend.collaboration;

import java.time.Instant;

/** Redis에 TTL로 저장되는 collaboration service 교차 런타임 계약(v1). 원문 ticket은 포함하지 않는다. */
public record CollaborationTicketPayload(
        int schemaVersion,
        long pageId,
        long userId,
        String displayName,
        String room,
        String permission,
        Instant issuedAt,
        Instant expiresAt) {

    public static final int SCHEMA_VERSION = 1;
    public static final String EDIT_PERMISSION = "EDIT";
}
