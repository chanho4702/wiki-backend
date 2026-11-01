package com.platform.wikibackend.collaboration;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 테스트 전용 — 테스트 JVM에는 Redis가 없다. 통합 테스트는 "커밋 후 어느 room이 발행됐는가"만 본다. */
@Component
@Primary
@Profile("!docs")
public class RecordingCollaborationRevokeSignal implements CollaborationRevokeSignal {

    public final List<List<String>> sent = new CopyOnWriteArrayList<>();

    public void reset() { sent.clear(); }

    @Override
    public void publish(Collection<String> rooms) { sent.add(List.copyOf(rooms)); }

    /** 발행된 room 전부(메시지 경계 무시). */
    public List<String> rooms() {
        return sent.stream().flatMap(List::stream).toList();
    }
}
