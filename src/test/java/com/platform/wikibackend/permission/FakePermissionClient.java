package com.platform.wikibackend.permission;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 테스트 전용 페이크 — test 소스셋의 컴포넌트 스캔으로 등록된다.
 * @Primary: SecurityConfig의 @ConditionalOnMissingBean은 빈 등록 순서에 따라 gRPC 빈이
 * 함께 생길 수 있으므로(알려진 fragility), 주입 우선권으로 확실히 대체한다.
 */
@Component
@org.springframework.context.annotation.Primary
@org.springframework.context.annotation.Profile("!docs")   // docs 프로필은 PublicReadPermissionClient를 그대로 검증한다
public class FakePermissionClient implements PermissionClient {

    private record Key(long userId, long spaceId, WikiAction action) {}

    private final Set<Key> allowed = new HashSet<>();
    private final Set<Long> allowAllUsers = new HashSet<>();
    private final Set<Long> globalAdmins = new HashSet<>();
    /** org 불능을 흉내 낼 사용자 — 판정이 503으로 전파되는 경로를 테스트가 밟는다 */
    private final Set<Long> unavailableFor = new HashSet<>();
    public final List<long[]> grantedAdmins = new ArrayList<>(); // [userId, spaceId] 기록
    public final List<Long> revokedSpaces = new ArrayList<>();   // 회수 호출된 spaceId 기록
    /** checkGlobal 호출 기록 — 전역 판정이 실제로 GLOBAL 경로를 타는지 본다 */
    public final List<GlobalCheck> globalChecks = new ArrayList<>();

    /** 전역 판정 한 건. resourceId는 GLOBAL이라 빈 값이다(proto 계약) — 그래서 들고 있지 않는다. */
    public record GlobalCheck(long userId, WikiAction action) {}

    /** 거부 사유(org denied_reason) — 넣지 않으면 빈 문자열이라 호출부의 기존 문구가 나온다 */
    private final Map<Long, String> deniedReasons = new HashMap<>();

    public void allow(long userId, long spaceId, WikiAction action) { allowed.add(new Key(userId, spaceId, action)); }

    /**
     * org의 GLOBAL grant 보유자를 흉내 낸다 — 전 스페이스가 보이고({@link #accessibleSpaces}) 전역
     * 관리자 판정({@link #checkGlobal})도 통과한다. 둘을 따로 보려면
     * {@link #allowAllSpaces}/{@link #allowGlobalAdmin}을 쓴다.
     */
    public void allowAll(long userId) { allowAllSpaces(userId); allowGlobalAdmin(userId); }
    /** 전 스페이스가 보이지만 전역 관리자는 아니다 — 판정이 grant 목록으로 되돌아가면 여기서 들킨다. */
    public void allowAllSpaces(long userId) { allowAllUsers.add(userId); }
    /** 전역 관리자(GLOBAL·ADMIN) */
    public void allowGlobalAdmin(long userId) { globalAdmins.add(userId); }
    /** 이 사용자의 판정은 org 불능처럼 터진다 — 503 전파 경로 검증용 */
    public void makeUnavailable(long userId) { unavailableFor.add(userId); }
    /** 이 사용자의 모든 거부에 사유를 실는다 — PENDING/SUSPENDED/DEACTIVATED면 문구가 바뀐다 */
    public void denyWithReason(long userId, String reason) { deniedReasons.put(userId, reason); }
    public void reset() {
        allowed.clear(); allowAllUsers.clear(); grantedAdmins.clear(); revokedSpaces.clear(); deniedReasons.clear();
        globalAdmins.clear(); unavailableFor.clear(); globalChecks.clear();
    }

    @Override
    public PermissionDecision check(long userId, long spaceId, WikiAction action) {
        failIfUnavailable(userId);
        return allowedFor(userId, spaceId, action)
                ? PermissionDecision.allow()
                : PermissionDecision.deny(deniedReasons.getOrDefault(userId, ""));
    }

    /**
     * 전역 판정. 스페이스 grant를 보지 않는다 — 실제 org의 {@code CheckPermission(GLOBAL, ADMIN)}처럼
     * GLOBAL grant만 본다.
     */
    @Override
    public PermissionDecision checkGlobal(long userId, WikiAction action) {
        globalChecks.add(new GlobalCheck(userId, action));
        failIfUnavailable(userId);
        return globalAdmins.contains(userId)
                ? PermissionDecision.allow()
                : PermissionDecision.deny(deniedReasons.getOrDefault(userId, ""));
    }

    /** org 불능은 판정 결과가 아니라 예외다 — GrpcPermissionClient가 던지는 것과 같은 타입. */
    private void failIfUnavailable(long userId) {
        if (unavailableFor.contains(userId)) {
            throw new com.platform.common.error.ServiceUnavailableException("권한 서비스에 연결할 수 없습니다");
        }
    }

    private boolean allowedFor(long userId, long spaceId, WikiAction action) {
        if (allowAllUsers.contains(userId) || allowed.contains(new Key(userId, spaceId, action))) return true;
        // org-service의 계층(EDITOR·ADMIN ⊃ COMMENTER)을 흉내 낸다 — 편집자에게 COMMENT를 따로 주지 않아도 된다
        return action == WikiAction.COMMENT
                && (allowed.contains(new Key(userId, spaceId, WikiAction.EDIT))
                    || allowed.contains(new Key(userId, spaceId, WikiAction.ADMIN)));
    }

    @Override
    public AccessScope accessibleSpaces(long userId) {
        if (allowAllUsers.contains(userId)) return new AccessScope(true, Set.of());
        Set<Long> ids = new HashSet<>();
        for (Key k : allowed) if (k.userId() == userId && k.action() == WikiAction.VIEW) ids.add(k.spaceId());
        return new AccessScope(false, ids);
    }

    @Override
    public boolean grantSpaceAdmin(long userId, long spaceId) {
        grantedAdmins.add(new long[]{userId, spaceId});
        allow(userId, spaceId, WikiAction.VIEW);
        allow(userId, spaceId, WikiAction.EDIT);
        allow(userId, spaceId, WikiAction.ADMIN);
        return true;
    }

    @Override
    public int revokeSpaceGrants(long spaceId) {
        revokedSpaces.add(spaceId);
        int revoked = 0;
        for (Key k : Set.copyOf(allowed)) {
            if (k.spaceId() == spaceId && allowed.remove(k)) revoked++;
        }
        return revoked;
    }
}
