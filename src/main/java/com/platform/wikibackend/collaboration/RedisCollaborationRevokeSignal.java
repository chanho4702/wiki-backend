package com.platform.wikibackend.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** {@code PUBLISH wiki:collaboration:revoke {"rooms":["page:<id>",...]}} — 큰 서브트리는 나눠 보낸다. */
@Component
@Slf4j
public class RedisCollaborationRevokeSignal implements CollaborationRevokeSignal {

    /** 한 메시지에 싣는 room 수. collaboration-service도 같은 상한으로 거른다. */
    static final int ROOMS_PER_MESSAGE = 200;

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RedisCollaborationRevokeSignal(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    @Override
    public void publish(Collection<String> rooms) {
        List<String> all = new ArrayList<>(rooms);
        for (int from = 0; from < all.size(); from += ROOMS_PER_MESSAGE) {
            List<String> chunk = all.subList(from, Math.min(all.size(), from + ROOMS_PER_MESSAGE));
            try {
                redis.convertAndSend(CHANNEL, json.writeValueAsString(Map.of("rooms", chunk)));
            } catch (Exception e) {
                // WARN인 이유: 신호가 빠져도 collaboration-service의 주기적 재인증이 늦게라도 끊는다.
                // 권한 변경 요청은 이미 커밋됐으므로 실패시키지 않는다.
                log.warn("공동 편집 권한 재확인 신호 발행 실패(비차단): rooms={}", chunk.size(), e);
            }
        }
    }
}
