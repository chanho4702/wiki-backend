package com.platform.wikibackend.permission;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.platform.proto.org.v1.*;
import com.platform.wikibackend.common.ServiceUnavailableException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * org-service gRPC 권한 클라이언트. 판정은 30초 Caffeine 캐시(권한 회수 반영 지연 30초 수용 — 스펙).
 * org 불능 시 fail-closed(false) — 가용성보다 인가 안전 우선.
 */
@Slf4j
public class GrpcPermissionClient implements PermissionClient {

    private record CacheKey(long userId, long spaceId, WikiAction action) {}

    private final PermissionServiceGrpc.PermissionServiceBlockingStub stub;
    private final Cache<CacheKey, Boolean> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(10_000)
            .build();

    public GrpcPermissionClient(PermissionServiceGrpc.PermissionServiceBlockingStub stub) {
        this.stub = stub;
    }

    @Override
    public boolean isAllowed(long userId, long spaceId, WikiAction action) {
        return cache.get(new CacheKey(userId, spaceId, action), k -> {
            try {
                return stub.checkPermission(CheckPermissionRequest.newBuilder()
                        .setUserId(k.userId())
                        .setResourceType(ResourceType.SPACE)
                        .setResourceId(String.valueOf(k.spaceId()))
                        .setAction(toProto(k.action()))
                        .build()).getAllowed();
            } catch (Exception e) {
                if (isUnavailable(e)) {
                    log.error("권한 서비스 불가 — 503 전파: user={} space={} action={}", k.userId(), k.spaceId(), k.action(), e);
                    throw new ServiceUnavailableException("권한 서비스에 연결할 수 없습니다");
                }
                log.warn("권한조회 실패 — fail-closed: user={} space={} action={}", k.userId(), k.spaceId(), k.action(), e);
                return false;
            }
        });
    }

    @Override
    public AccessScope accessibleSpaces(long userId) {
        try {
            ListUserGrantsResponse res = stub.listUserGrants(
                    ListUserGrantsRequest.newBuilder().setUserId(userId).build()); // UNSPECIFIED = 전체
            boolean global = res.getGrantsList().stream()
                    .anyMatch(g -> g.getResourceType() == ResourceType.GLOBAL);
            if (global) return new AccessScope(true, Set.of());
            Set<Long> ids = res.getGrantsList().stream()
                    .filter(g -> g.getResourceType() == ResourceType.SPACE)
                    .map(g -> Long.parseLong(g.getResourceId()))
                    .collect(Collectors.toSet());
            return new AccessScope(false, ids);
        } catch (Exception e) {
            if (isUnavailable(e)) {
                log.error("권한 서비스 불가 — 503 전파: user={}", userId, e);
                throw new ServiceUnavailableException("권한 서비스에 연결할 수 없습니다");
            }
            log.warn("grant 목록 조회 실패 — fail-closed(빈 목록): user={}", userId, e);
            return new AccessScope(false, Set.of());
        }
    }

    @Override
    public boolean grantSpaceAdmin(long userId, long spaceId) {
        try {
            return stub.createGrant(CreateGrantRequest.newBuilder()
                    .setUserId(userId)
                    .setResourceType(ResourceType.SPACE)
                    .setResourceId(String.valueOf(spaceId))
                    .setRole(Role.ROLE_ADMIN)
                    .build()).getCreated();
        } catch (Exception e) {
            log.warn("자동 ADMIN 부여 실패: user={} space={}", userId, spaceId, e);
            return false;
        }
    }

    @Override
    public int revokeSpaceGrants(long spaceId) {
        try {
            return stub.revokeGrant(RevokeGrantRequest.newBuilder()
                    .setResourceType(ResourceType.SPACE)
                    .setResourceId(String.valueOf(spaceId))
                    .build()).getRevoked();
        } catch (Exception e) {
            // 스페이스는 이미 지워졌다 — 되돌릴 수 없으니 실패를 전파하지 않고 남은 고아 grant를 로그로 알린다
            log.warn("스페이스 grant 회수 실패(고아 grant 잔존): space={}", spaceId, e);
            return 0;
        }
    }

    /** gRPC 전송/가용성 장애(org-service 다운·타임아웃)만 판별 — 이 경우에만 503으로 전파한다. */
    private static boolean isUnavailable(Throwable e) {
        if (e instanceof StatusRuntimeException sre) {
            Status.Code code = sre.getStatus().getCode();
            return code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED;
        }
        return false;
    }

    private static Action toProto(WikiAction a) {
        return switch (a) {
            case VIEW -> Action.VIEW;
            case EDIT -> Action.EDIT;
            case ADMIN -> Action.ADMIN;
        };
    }
}
