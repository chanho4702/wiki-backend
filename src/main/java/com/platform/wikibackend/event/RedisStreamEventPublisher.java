package com.platform.wikibackend.event;

import com.platform.proto.events.v1.EventEnvelope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;

/** XADD platform:events:v1 MAXLEN ~100000 — payload는 proto 바이너리. */
@Component
@ConditionalOnProperty(value = "platform.events.enabled", havingValue = "true", matchIfMissing = true)
public class RedisStreamEventPublisher implements EventPublisher {

    public static final String STREAM = "platform:events:v1";
    private static final long MAXLEN = 100_000;

    private final StringRedisTemplate redis;

    public RedisStreamEventPublisher(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public void publish(EventEnvelope event) {
        byte[] stream = STREAM.getBytes(StandardCharsets.UTF_8);
        Map<byte[], byte[]> fields = Map.of("payload".getBytes(StandardCharsets.UTF_8), event.toByteArray());
        redis.execute((RedisCallback<RecordId>) (RedisConnection conn) ->
                conn.streamCommands().xAdd(MapRecord.create(stream, fields),
                        XAddOptions.maxlen(MAXLEN).approximateTrimming(true)));
    }
}
