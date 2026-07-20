package com.platform.wikibackend.event;

import com.platform.proto.events.v1.EventEnvelope;

/** 이벤트 버스 창구 — 현재 구현은 Redis Streams. L 티어에서 Kafka 교체 시 이 계약 뒤만 바뀐다. */
public interface EventPublisher {
    void publish(EventEnvelope event);
}
