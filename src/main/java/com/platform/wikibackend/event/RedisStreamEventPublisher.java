package com.platform.wikibackend.event;

import com.platform.proto.events.v1.EventEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

import static org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;

/** XADD platform:events:v1 MAXLEN ~100000 — payload는 proto 바이너리. */
@Component
@Slf4j
@ConditionalOnProperty(value = "platform.events.enabled", havingValue = "true", matchIfMissing = true)
public class RedisStreamEventPublisher implements EventPublisher {

    public static final String STREAM = "platform:events:v1";
    private static final long MAXLEN = 100_000;

    private final StringRedisTemplate redis;

    public RedisStreamEventPublisher(StringRedisTemplate redis) { this.redis = redis; }

    /**
     * 기동 직후 연결된 Redis가 스트림(XADD, 5.0+)을 지원하는지 확인한다.
     *
     * 이게 없어서 dev가 localhost:6379의 구버전 Redis(3.2, 스트림 없음)에 붙은 걸 12일간 몰랐다 —
     * 발행은 매번 실패했지만 비차단이라 화면은 멀쩡했고 스트림만 계속 비어 있었다(2026-08-02).
     * 부팅은 막지 않는다. Redis는 선택적 의존이고, 여기서 죽이면 이벤트 때문에 서비스가 못 뜬다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void verifyStreamSupport() {
        try {
            Properties info = redis.execute((RedisCallback<Properties>) conn -> conn.serverCommands().info("server"));
            String version = info == null ? null : info.getProperty("redis_version");
            if (version == null) {
                log.warn("Redis 버전을 확인하지 못했습니다 — 스트림 지원 여부 미확인");
                return;
            }
            int major = Integer.parseInt(version.split("\\.")[0]);
            if (major < 5) {
                log.error("연결된 Redis {}는 스트림(XADD)을 지원하지 않습니다(5.0+ 필요) — "
                        + "이벤트가 전부 유실됩니다. 연결 대상을 확인하세요(호스트에 구버전 Redis가 "
                        + "127.0.0.1:6379를 선점하고 있을 수 있습니다).", version);
            } else {
                log.info("이벤트 스트림 대상 Redis {} — 스트림 지원 확인", version);
            }
        } catch (Exception e) {
            log.warn("Redis 스트림 지원 확인 실패(발행 시 재확인됨)", e);
        }
    }

    @Override
    public void publish(EventEnvelope event) {
        byte[] stream = STREAM.getBytes(StandardCharsets.UTF_8);
        Map<byte[], byte[]> fields = Map.of("payload".getBytes(StandardCharsets.UTF_8), event.toByteArray());
        redis.execute((RedisCallback<RecordId>) (RedisConnection conn) ->
                conn.streamCommands().xAdd(MapRecord.create(stream, fields),
                        XAddOptions.maxlen(MAXLEN).approximateTrimming(true)));
    }
}
