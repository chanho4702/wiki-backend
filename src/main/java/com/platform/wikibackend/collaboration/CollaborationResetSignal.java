package com.platform.wikibackend.collaboration;

/**
 * 공동 초안이 다른 경로 저장으로 리셋됐음을 collaboration-service 노드들에 알린다.
 *
 * 정확성은 collaboration-service의 세대 가드(store가 generation으로 UPDATE)가 보장한다 — 이 신호는
 * 이미 열린 세션을 즉시 끊기 위한 최적화다. 그래서 구현은 실패를 던지지 않고 삼킨다.
 */
public interface CollaborationResetSignal {

    /** Redis pub/sub 채널. collaboration-service가 구독한다(교차 런타임 계약). */
    String CHANNEL = "wiki:collaboration:reset";

    /** @param resetEpoch 리셋 후 reset_epoch. 수신 노드는 기억한 epoch가 이보다 작을 때만 리셋한다. */
    void publish(String room, long resetEpoch);
}
