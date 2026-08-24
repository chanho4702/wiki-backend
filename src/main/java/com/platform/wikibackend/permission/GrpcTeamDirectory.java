package com.platform.wikibackend.permission;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.platform.proto.org.v1.ListUserTeamsRequest;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import com.platform.wikibackend.common.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;

/**
 * org-service 팀 멤버십 클라이언트(proto 0.7.0 ListUserTeams) — W18 페이지 제한의 TEAM 주체 판정.
 * GrpcPermissionClient와 같은 규약: 30초 Caffeine 캐시(팀 탈퇴 반영 지연 30초 수용),
 * UNAVAILABLE/DEADLINE만 503, 그 외 오류(구버전 org의 UNIMPLEMENTED 포함)는 fail-closed(빈 목록)
 * — 멤버십을 모르면 TEAM 제한을 통과시키지 않는다(인가 안전 우선).
 */
@Slf4j
public class GrpcTeamDirectory implements TeamDirectory {

    private final PermissionServiceGrpc.PermissionServiceBlockingStub stub;
    private final Cache<Long, List<Long>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(10_000)
            .build();

    public GrpcTeamDirectory(PermissionServiceGrpc.PermissionServiceBlockingStub stub) {
        this.stub = stub;
    }

    @Override
    public List<Long> teamsOf(long userId) {
        return cache.get(userId, id -> {
            try {
                return List.copyOf(stub.listUserTeams(
                        ListUserTeamsRequest.newBuilder().setUserId(id).build()).getTeamIdsList());
            } catch (Exception e) {
                if (GrpcPermissionClient.isUnavailable(e)) {
                    log.error("org 팀 조회 불가 — 503 전파: user={}", id, e);
                    throw new ServiceUnavailableException("권한 서비스에 연결할 수 없습니다");
                }
                log.warn("팀 멤버십 조회 실패 — fail-closed(빈 목록): user={}", id, e);
                return List.of();
            }
        });
    }
}
