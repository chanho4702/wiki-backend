package com.platform.wikibackend.event;

import com.platform.proto.events.v1.EventEnvelope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 테스트 전용 — platform.events.enabled=false로 Redis 구현이 빠진 자리를 채운다. */
@Component
public class RecordingEventPublisher implements EventPublisher {

    public final List<EventEnvelope> events = new ArrayList<>();

    public void reset() { events.clear(); }

    @Override
    public void publish(EventEnvelope event) { events.add(event); }
}
