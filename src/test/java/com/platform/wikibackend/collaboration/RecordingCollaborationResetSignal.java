package com.platform.wikibackend.collaboration;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 테스트 전용 — 테스트 JVM에는 Redis가 없다. Redis 구현은 {@code RedisCollaborationResetSignalTest}가
 * 따로 검증하고, 통합 테스트는 "커밋 후에 무엇이 발행됐는가"만 본다.
 */
@Component
@Primary
@org.springframework.context.annotation.Profile("!docs")
public class RecordingCollaborationResetSignal implements CollaborationResetSignal {

    public record Sent(String room, long resetEpoch) {
    }

    public final List<Sent> sent = new CopyOnWriteArrayList<>();

    public void reset() { sent.clear(); }

    @Override
    public void publish(String room, long resetEpoch) { sent.add(new Sent(room, resetEpoch)); }
}
