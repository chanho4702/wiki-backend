package com.platform.wikibackend.migration.confluence.restriction;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 기본 구현 — 아무도 대조하지 못한다.
 *
 * 이 클래스가 존재하는 이유는 "아직 못 한다"를 코드에 명시적으로 두기 위해서다. org-service에는
 * 이름·이메일로 사용자를 찾는 gRPC가 없고(common-proto 0.14.0), 이관 모듈이 계정을 새로 만들지
 * 않는 것은 기획의 전제다. 그래서 원본 제한은 전부 미매핑으로 처리되고, 호출부가 fail-closed로
 * 닫는다 — **조용히 공개로 푸는 경로는 어디에도 없다**.
 *
 * org에 조회 API가 생기면 이 빈을 대체하는 구현을 하나 넣으면 된다(@Primary 또는 이 클래스 교체).
 */
@Component
public class UnmappedMigrationPrincipalResolver implements MigrationPrincipalResolver {

    @Override
    public Optional<Long> resolveUser(SourceUser user) {
        return Optional.empty();
    }

    @Override
    public Optional<Long> resolveTeam(String groupName) {
        return Optional.empty();
    }
}
