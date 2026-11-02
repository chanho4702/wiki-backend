package com.platform.wikibackend.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * {@code PUBLISH wiki:collaboration:revoke {"rooms":["page:<id>",...],"revokedAt":"<ISO-8601>"}} — 큰 서브트리는
 * 나눠 보낸다. revokedAt은 커밋 후 발행 시각(티켓 issuedAt과 같은 시스템 UTC 시계)이라, collaboration-service가
 * 회수 전에 발급된 티켓으로 늦게 붙은 연결을 이 값과 비교해 닫는다.
 */
@Component
@Slf4j
public class RedisCollaborationRevokeSignal implements CollaborationRevokeSignal {

    /** 한 메시지에 싣는 room 수. collaboration-service도 같은 상한으로 거른다. */
    static final int ROOMS_PER_MESSAGE = 200;

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    private final Clock clock;

    @Autowired
    public RedisCollaborationRevokeSignal(StringRedisTemplate redis, ObjectMapper json) {
        this(redis, json, Clock.systemUTC());
    }

    RedisCollaborationRevokeSignal(StringRedisTemplate redis, ObjectMapper json, Clock clock) {
        this.redis = redis;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void publish(Collection<String> rooms) {
        List<String> all = new ArrayList<>(rooms);
        String revokedAt = clock.instant().toString();
        for (int from = 0; from < all.size(); from += ROOMS_PER_MESSAGE) {
            List<String> chunk = all.subList(from, Math.min(all.size(), from + ROOMS_PER_MESSAGE));
            try {
                redis.convertAndSend(CHANNEL, json.writeValueAsString(Map.of("rooms", chunk, "revokedAt", revokedAt)));
            } catch (Exception e) {
                // WARN인 이유: 신호가 빠져도 collaboration-service의 주기적 재인증이 늦게라도 끊는다.
                // 권한 변경 요청은 이미 커밋됐으므로 실패시키지 않는다.
                log.warn("공동 편집 권한 재확인 신호 발행 실패(비차단): rooms={}", chunk.size(), e);
            }
        }
    }
}
