package com.platform.wikibackend.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@code PUBLISH wiki:collaboration:reset {"room":"page:<id>","resetEpoch":<n>}} — 티켓과 같은 Redis 연결을 쓴다. */
@Component
@Slf4j
public class RedisCollaborationResetSignal implements CollaborationResetSignal {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RedisCollaborationResetSignal(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    @Override
    public void publish(String room, long resetEpoch) {
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("room", room);
            message.put("resetEpoch", resetEpoch);
            redis.convertAndSend(CHANNEL, json.writeValueAsString(message));
        } catch (Exception e) {
            // WARN인 이유: 신호가 빠져도 옛 세션의 다음 저장이 세대 가드(0행)에 걸려 그때 끊긴다 —
            // 데이터는 안전하고 늦게 끊길 뿐이다. 저장 요청은 이미 커밋됐으므로 실패시키지 않는다.
            log.warn("공동 초안 리셋 신호 발행 실패(비차단): room={} resetEpoch={}", room, resetEpoch, e);
        }
    }
}
