package com.platform.wikibackend.directory;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 테스트 전용 사용자 디렉터리 페이크 — test 소스셋의 컴포넌트 스캔으로 등록된다.
 *
 * <p><b>기본값이 "빈 디렉터리"인 것이 중요하다.</b> 이름 보강·메일 수신자 결정은 "원장이 모르는 사람"을 폴백으로
 * 읽으므로 {@link #lookup}은 넣은 사람만 돌려준다.
 *
 * <p>계정 상태 게이트 창구({@link #accountStatus})는 다르다: 실제 게이트는 org에 없는 사람을 승인 대기로 막는다
 * (2026-10-24 fail-closed). 상태를 다루지 않는 기존 테스트가 그 때문에 깨지지 않게, 이 대역은 <b>모르는 사람을
 * ACTIVE로 답한다</b>(대역 쪽 편의). "org에 없음"을 검증하는 테스트는 {@link #markAbsent}로 표시한다.
 */
@Component
@org.springframework.context.annotation.Primary
@org.springframework.context.annotation.Profile("!docs")
public class FakeMemberDirectory implements MemberDirectory {

    private final Map<Long, DirectoryMember> members = new LinkedHashMap<>();
    private final List<List<Long>> calls = new CopyOnWriteArrayList<>();

    private final Set<Long> absent = ConcurrentHashMap.newKeySet();

    private volatile boolean unavailable = false;
    private volatile boolean failed = false;

    public void put(long id, String name, String email, String status) {
        members.put(id, new DirectoryMember(id, name, email, status, "HUMAN"));
    }

    /** 게이트 창구에서 이 사람을 "org에 없음"으로 답한다 */
    public void markAbsent(long id) { absent.add(id); }

    public void setUnavailable(boolean value) { this.unavailable = value; }

    public void setFailed(boolean value) { this.failed = value; }

    /** 조회 호출 기록 — 캐시가 실제로 왕복을 줄이는지 본다 */
    public List<List<Long>> calls() { return calls; }

    public void reset() {
        members.clear();
        absent.clear();
        calls.clear();
        unavailable = false;
        failed = false;
    }

    @Override
    public Lookup lookup(Collection<Long> ids) {
        calls.add(List.copyOf(ids));
        if (unavailable) return new Lookup(Outcome.UNAVAILABLE, Map.of());
        if (failed) return new Lookup(Outcome.FAILED, Map.of());
        Map<Long, DirectoryMember> found = new LinkedHashMap<>();
        for (Long id : new ArrayList<>(ids)) {
            DirectoryMember member = members.get(id);
            if (member != null) found.put(id, member);
        }
        return Lookup.ok(found);
    }

    @Override
    public Lookup accountStatus(long userId) {
        Lookup lookup = lookup(List.of(userId)); // 호출 기록·장애 흉내는 lookup과 같다
        if (!lookup.ok() || lookup.members().containsKey(userId) || absent.contains(userId)) return lookup;
        return Lookup.ok(Map.of(userId, new DirectoryMember(userId, "", "", "ACTIVE", "HUMAN")));
    }
}
