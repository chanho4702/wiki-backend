package com.platform.wikibackend.collaboration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.error.ServiceUnavailableException;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.page.PageService;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.space.SpaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 기존 JWT로 EDIT 권한을 확인한 뒤 WebSocket upgrade 전용 1회용 ticket을 발급한다.
 *
 * Redis key에는 원문 대신 SHA-256만 두므로 운영자가 key를 조회해도 접속 자격증명이 노출되지 않는다.
 * collaboration service는 같은 방식으로 hash한 key를 GETDEL해 원자적으로 1회만 소비한다.
 */
@Service
@Slf4j
public class CollaborationTicketService {

    public static final String KEY_PREFIX = "wiki:collaboration:ticket:v1:";
    public static final String WEBSOCKET_PATH = "/api/wiki/collaboration";
    private static final int TOKEN_BYTES = 32;
    private static final Duration MAX_TTL = Duration.ofMinutes(5);

    private final PageService pages;
    private final SpaceService spaces;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration ttl;
    private final Clock clock;
    private final SecureRandom random;

    @Autowired
    public CollaborationTicketService(
            PageService pages,
            SpaceService spaces,
            StringRedisTemplate redis,
            ObjectMapper json,
            @Value("${platform.wiki.collaboration.ticket-ttl:PT1M}") Duration ttl) {
        this(pages, spaces, redis, json, ttl, Clock.systemUTC(), new SecureRandom());
    }

    CollaborationTicketService(
            PageService pages,
            SpaceService spaces,
            StringRedisTemplate redis,
            ObjectMapper json,
            Duration ttl,
            Clock clock,
            SecureRandom random) {
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("collaboration ticket TTL은 0초 초과 5분 이하여야 합니다");
        }
        this.pages = pages;
        this.spaces = spaces;
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
        this.clock = clock;
        this.random = random;
    }

    public CollaborationTicketResponse issue(long userId, String displayName, long pageId) {
        // W18: space EDIT + 페이지 제한까지 — 티켓은 협업 편집의 입장권이라 우회되면 제한이 무력하다
        Page page = pages.getEditable(userId, pageId);

        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(ttl);
        String room = "page:" + pageId;
        String ticket = newToken();
        CollaborationTicketPayload payload = new CollaborationTicketPayload(
                CollaborationTicketPayload.SCHEMA_VERSION,
                pageId,
                userId,
                normalizeDisplayName(displayName, userId),
                room,
                CollaborationTicketPayload.EDIT_PERMISSION,
                issuedAt,
                expiresAt);

        try {
            redis.opsForValue().set(KEY_PREFIX + sha256(ticket), serialize(payload), ttl);
        } catch (DataAccessException e) {
            // Redis가 없는데 ticket을 발급한 척하면 WebSocket에서 뒤늦게 실패한다. 발급 단계에서 fail-closed.
            throw new ServiceUnavailableException("공동 편집 세션을 시작할 수 없습니다", e);
        }
        log.info("collaboration ticket 발급: page={} user={} expiresAt={}", pageId, userId, expiresAt);
        return new CollaborationTicketResponse(ticket, room, WEBSOCKET_PATH, expiresAt);
    }

    static String sha256(String ticket) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(ticket.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String serialize(CollaborationTicketPayload payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("collaboration ticket payload 직렬화 실패", e);
        }
    }

    private static String normalizeDisplayName(String displayName, long userId) {
        if (displayName == null) return "사용자 #" + userId;
        String normalized = displayName.replaceAll("\\p{Cc}", " ").trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) return "사용자 #" + userId;
        int codePoints = normalized.codePointCount(0, normalized.length());
        int end = normalized.offsetByCodePoints(0, Math.min(codePoints, 200));
        return normalized.substring(0, end);
    }
}
