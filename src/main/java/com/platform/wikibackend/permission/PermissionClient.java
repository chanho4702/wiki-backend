package com.platform.wikibackend.permission;

/** org-service 권한 연동 창구 — 테스트는 페이크로 대체. */
public interface PermissionClient {

    /**
     * 스페이스 단위 판정. 거부면 사유(common-proto 0.16.0 {@code denied_reason})까지 온다 —
     * 403 문구를 정하는 호출부는 {@code isAllowed}가 아니라 이쪽을 쓴다.
     */
    PermissionDecision check(long userId, long spaceId, WikiAction action);

    /**
     * 전역 판정 — GLOBAL 리소스(proto 계약상 {@code resource_id}는 빈 값). 전역 관리자는
     * {@code checkGlobal(userId, ADMIN)}이다(2026-09-12, alm-backend와 같은 판정).
     *
     * <p>{@link #accessibleSpaces}의 {@code all()}로 전역 관리자를 보던 경로를 이쪽으로 옮겼다.
     * 이유는 <b>거부 사유</b>다: grant 목록은 "GLOBAL grant가 없다"까지만 말하므로 승인 대기·정지된
     * 계정도 그냥 "권한 없음"이 되어, 사용자가 할 조치(관리자에게 승인 요청)를 알 수 없었다.
     * CheckPermission은 {@code denied_reason}을 싣는다 — 403 문구가 사실을 말할 수 있다.
     */
    PermissionDecision checkGlobal(long userId, WikiAction action);

    /**
     * 사유가 필요 없는 곳(목록·트리·알림 필터처럼 예외를 던지지 않고 거르기만 하는 경로)의 편의 메서드.
     * 판정 자체는 {@link #check}와 같다 — 캐시도 공유한다.
     */
    default boolean isAllowed(long userId, long spaceId, WikiAction action) {
        return check(userId, spaceId, action).allowed();
    }

    /**
     * 볼 수 있는 스페이스 범위 — 목록·검색·라벨처럼 <b>거르기만 하는</b> 경로가 쓴다.
     * {@code all()}은 "전 스페이스가 보인다"는 뜻이고 <b>전역 관리자 판정이 아니다</b>
     * (그 판정은 {@link #checkGlobal}). 실패하면 빈 범위다(fail-closed) — 목록이 "권한 없음"이
     * 아니라 "결과 없음"으로 보이므로 조용하지만, 남의 스페이스를 흘리는 것보다는 낫다.
     */
    AccessScope accessibleSpaces(long userId);

    /** 스페이스 생성자 자동 ADMIN 부여. 이미 있으면 false(멱등). 실패 시 예외 아님 — false. */
    boolean grantSpaceAdmin(long userId, long spaceId);

    /**
     * 스페이스 삭제 시 그 스페이스에 걸린 grant 전부 회수. 회수한 수를 반환(대상 없으면 0).
     * 실패해도 예외가 아니다 — 스페이스는 이미 지워졌고, 남은 고아 grant 때문에 삭제를
     * 되돌릴 수는 없다. 대신 경고 로그로 남긴다.
     */
    int revokeSpaceGrants(long spaceId);
}
