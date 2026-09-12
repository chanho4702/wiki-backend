package com.platform.wikibackend.permission;

import com.platform.common.error.ForbiddenException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 전역 관리자 판정 — org-service {@code CheckPermission(GLOBAL, ADMIN)} 하나가 진실 소스다
 * (2026-09-12, alm-backend {@code GlobalAdminGuard}와 같은 판정).
 *
 * <p>그 전에는 {@code accessibleSpaces(userId).all()}(ListUserGrants에 GLOBAL grant가 있는가)로 봤다.
 * 판정 결과는 같았지만 <b>거부 사유가 없었다</b>: 승인 대기·정지·비활성된 계정도 "전역 관리자만 볼 수
 * 있습니다"로 떨어져, 사용자가 해야 할 조치(관리자에게 승인·해제 요청)를 알 수 없었다. 게다가 grant
 * 목록은 전 스페이스 범위를 구하는 용도와 섞여 있어, 목록 필터의 의미 변화가 인가까지 번질 수 있었다.
 *
 * <p>판정이 세 갈래인 것이 요점이다:
 * <ul>
 *   <li>허용 → 통과</li>
 *   <li>거부 → 403 {@code {"error": ...}}. 계정 상태로 막힌 것이면 그 사실을 그대로 말하고
 *       (승인 대기·정지·비활성), 권한만 모자라면 호출부가 준 문구를 쓴다</li>
 *   <li>org 불능 → 503. <b>권한 없음으로 오인하지 않는다</b> — org가 죽은 동안 관리자에게
 *       "당신은 관리자가 아닙니다"라고 답하면 사람이 잘못된 조치를 한다.</li>
 * </ul>
 * 503은 {@link GrpcPermissionClient}가 던지는 {@code ServiceUnavailableException}이 그대로 올라간다 —
 * 이 클래스는 장애를 판단하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class GlobalAdminGuard {

    private final PermissionClient permissions;

    /**
     * 전역 관리자가 아니면 던진다.
     *
     * @param fallbackMessage 계정 상태 문제가 아닐 때 쓸 403 문구. 경로마다 다르다
     *                        ("플랫폼 현황은 …", "스페이스 삭제 기록은 …") — 사용자가 무엇을 보려다
     *                        막혔는지 알려 주는 쪽이 낫기 때문에 하나로 통일하지 않는다.
     */
    public void require(long userId, String fallbackMessage) {
        PermissionDecision decision = permissions.checkGlobal(userId, WikiAction.ADMIN);
        if (decision.allowed()) return;
        String accountMessage = decision.accountMessage();
        throw new ForbiddenException(accountMessage == null ? fallbackMessage : accountMessage);
    }
}
