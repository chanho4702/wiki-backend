package com.platform.wikibackend.collaboration;

import java.util.Collection;

/**
 * 열린 공동 편집 세션에 "권한을 다시 확인하라"고 알린다(43번 높음-1).
 *
 * 공동 편집 인증은 접속할 때 받은 1회용 티켓 한 번뿐이라, 휴지통 보내기·이동·페이지 제한 변경으로 편집
 * 권한을 잃은 사람도 연결이 끊길 때까지 계속 읽고 쓴다. 이 신호를 받은 collaboration-service는 그 room의
 * 연결을 닫고, 클라이언트는 새 티켓(EDIT 재판정)으로만 다시 붙는다 — 권한이 없으면 거기서 막힌다.
 *
 * 여기서 놓친 변경(org 쪽 역할·그룹 변경 등)은 collaboration-service의 주기적 재인증이 상한을 둔다.
 * 그래서 구현은 실패를 던지지 않는다.
 */
public interface CollaborationRevokeSignal {

    /** Redis pub/sub 채널. collaboration-service가 구독한다(교차 런타임 계약). */
    String CHANNEL = "wiki:collaboration:revoke";

    /** @param rooms {@code page:<id>} 목록. 비어 있으면 아무것도 하지 않는다. */
    void publish(Collection<String> rooms);
}
