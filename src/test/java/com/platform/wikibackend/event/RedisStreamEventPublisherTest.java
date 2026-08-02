package com.platform.wikibackend.event;

import com.platform.proto.events.v1.EventEnvelope;
import com.platform.proto.events.v1.PageCreated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실 Redis에 대고 발행 경로를 직접 실행한다.
 *
 * 나머지 테스트는 전부 RecordingEventPublisher(페이크)를 쓰고 application-test.yml이
 * events를 꺼두기 때문에, 이 클래스가 없으면 XADD 호출은 **한 번도 실행되지 않는다** —
 * 실제로 운영에서 스트림이 빈 채 발견됐다(2026-08-02).
 */
@Testcontainers
class RedisStreamEventPublisherTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @BeforeEach
    void clearStream() {
        // static 컨테이너를 두 테스트가 공유한다 — 앞 테스트가 남긴 엔트리가 개수 단언을 깨뜨린다
        StringRedisTemplate t = template();
        t.execute((org.springframework.data.redis.core.RedisCallback<Long>) conn ->
                conn.keyCommands().del(RedisStreamEventPublisher.STREAM.getBytes(StandardCharsets.UTF_8)));
    }

    private StringRedisTemplate template() {
        LettuceConnectionFactory cf = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
        cf.afterPropertiesSet();
        StringRedisTemplate t = new StringRedisTemplate(cf);
        t.afterPropertiesSet();
        return t;
    }

    private static EventEnvelope sampleEvent() {
        return EventEnvelope.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOccurredAt(System.currentTimeMillis())
                .setActorId(1L)
                .setSource("wiki-backend")
                .setPageCreated(PageCreated.newBuilder().setPageId(7L).setSpaceId(1L).setTitle("제목 없음"))
                .build();
    }

    @Test
    void publish하면_스트림에_엔트리가_쌓인다() {
        StringRedisTemplate t = template();
        RedisStreamEventPublisher publisher = new RedisStreamEventPublisher(t);

        publisher.publish(sampleEvent());

        Long len = t.execute((org.springframework.data.redis.core.RedisCallback<Long>) conn ->
                conn.streamCommands().xLen(RedisStreamEventPublisher.STREAM.getBytes(StandardCharsets.UTF_8)));
        assertThat(len).isEqualTo(1L);
    }

    @Test
    void 실린_payload가_원본_proto로_역직렬화된다() throws Exception {
        StringRedisTemplate t = template();
        RedisStreamEventPublisher publisher = new RedisStreamEventPublisher(t);
        EventEnvelope sent = sampleEvent();

        publisher.publish(sent);

        List<org.springframework.data.redis.connection.stream.ByteRecord> records =
                t.execute((org.springframework.data.redis.core.RedisCallback<List<org.springframework.data.redis.connection.stream.ByteRecord>>) conn ->
                        conn.streamCommands().xRange(
                                RedisStreamEventPublisher.STREAM.getBytes(StandardCharsets.UTF_8),
                                org.springframework.data.domain.Range.unbounded()));

        assertThat(records).isNotEmpty();
        // Map<byte[],byte[]>를 byte[] 키로 get()하면 배열 동일성 비교라 항상 null이다 — 키를 디코딩해 찾는다
        byte[] payload = records.get(records.size() - 1).getValue().entrySet().stream()
                .filter(e -> "payload".equals(new String(e.getKey(), StandardCharsets.UTF_8)))
                .map(java.util.Map.Entry::getValue)
                .findFirst().orElse(null);
        assertThat(payload).isNotNull();
        EventEnvelope received = EventEnvelope.parseFrom(payload);
        assertThat(received.getEventId()).isEqualTo(sent.getEventId());
        assertThat(received.hasPageCreated()).isTrue();
        assertThat(received.getPageCreated().getPageId()).isEqualTo(7L);
    }
}
