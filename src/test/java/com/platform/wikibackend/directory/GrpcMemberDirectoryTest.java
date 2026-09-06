package com.platform.wikibackend.directory;

import com.platform.proto.org.v1.GetMembersRequest;
import com.platform.proto.org.v1.GetMembersResponse;
import com.platform.proto.org.v1.MemberInfo;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GetMembers} 어댑터 — 청크 분할·중복 제거·부분 실패 강등을 in-process 가짜 org로 본다.
 *
 * <p>부분 실패가 요점이다: 200명씩 끊어 보내다 한 묶음만 실패하면 그 사람들이 응답에서 빠지는데,
 * 결과를 OK로 표시하면 호출측이 "그런 사람 없다"로 읽는다. 계정 상태 게이트에서 그 오해는
 * 정지된 계정을 통과시키는 것과 같다.
 */
class GrpcMemberDirectoryTest {

    static class StubOrg extends PermissionServiceGrpc.PermissionServiceImplBase {
        final List<List<Long>> received = new CopyOnWriteArrayList<>();
        /** 요청 순번(0부터) → 그 요청에 돌려줄 오류. 없으면 성공 */
        final Map<Integer, Status> failures = new ConcurrentHashMap<>();

        void failAt(int requestIndex, Status status) {
            failures.put(requestIndex, status);
        }

        @Override public void getMembers(GetMembersRequest req, StreamObserver<GetMembersResponse> out) {
            int index = received.size();
            received.add(new ArrayList<>(req.getIdsList()));
            Status failure = failures.get(index);
            if (failure != null) {
                out.onError(failure.asRuntimeException());
                return;
            }
            GetMembersResponse.Builder res = GetMembersResponse.newBuilder();
            for (long id : req.getIdsList()) {
                res.addMembers(MemberInfo.newBuilder()
                        .setId(id)
                        .setDisplayName("사람" + id)
                        .setEmail("u" + id + "@org.example")
                        .setStatus("ACTIVE")
                        .setKind("HUMAN"));
            }
            out.onNext(res.build());
            out.onCompleted();
        }
    }

    StubOrg stubOrg = new StubOrg();
    Server server;
    ManagedChannel channel;
    GrpcMemberDirectory directory;

    @BeforeEach
    void setup() throws IOException {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(stubOrg).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        directory = new GrpcMemberDirectory(PermissionServiceGrpc.newBlockingStub(channel));
    }

    @AfterEach
    void teardown() throws InterruptedException {
        channel.shutdownNow();
        server.shutdownNow();
        channel.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS);
        server.awaitTermination();
    }

    @Test
    void 사람을_id로_읽는다() {
        MemberDirectory.Lookup lookup = directory.lookup(List.of(1L, 2L));

        assertThat(lookup.ok()).isTrue();
        assertThat(lookup.members()).hasSize(2);
        assertThat(lookup.members().get(1L).displayName()).isEqualTo("사람1");
        assertThat(lookup.members().get(1L).email()).isEqualTo("u1@org.example");
        assertThat(lookup.members().get(1L).status()).isEqualTo("ACTIVE");
    }

    /** proto가 정한 한 요청 상한이 200이다 — 넘겨 보내면 INVALID_ARGUMENT다 */
    @Test
    void 이백명을_넘으면_끊어_보낸다() {
        List<Long> ids = LongStream.rangeClosed(1, 450).boxed().toList();

        MemberDirectory.Lookup lookup = directory.lookup(ids);

        assertThat(lookup.ok()).isTrue();
        assertThat(lookup.members()).hasSize(450);
        assertThat(stubOrg.received).hasSize(3);
        assertThat(stubOrg.received.get(0)).hasSize(200);
        assertThat(stubOrg.received.get(1)).hasSize(200);
        assertThat(stubOrg.received.get(2)).hasSize(50);
    }

    @Test
    void 같은_id를_여러_번_줘도_한_번만_묻는다() {
        directory.lookup(List.of(3L, 3L, 4L, 3L));

        assertThat(stubOrg.received).hasSize(1);
        assertThat(stubOrg.received.get(0)).containsExactly(3L, 4L);
    }

    /**
     * 묶음 하나가 불능이면 결과 전체가 불능이다. 부분 성공을 OK로 주면 빠진 사람이 "없는 사람"이 되고,
     * 계정 상태 게이트가 그것을 통과로 읽는다.
     */
    @Test
    void 청크_하나만_불능이어도_전체가_불능이다() {
        stubOrg.failAt(1, Status.UNAVAILABLE);

        MemberDirectory.Lookup lookup = directory.lookup(LongStream.rangeClosed(1, 300).boxed().toList());

        assertThat(lookup.outcome()).isEqualTo(MemberDirectory.Outcome.UNAVAILABLE);
        assertThat(lookup.members()).hasSize(200); // 성공한 묶음의 결과는 그대로 준다
    }

    /** 가용성 장애가 아닌 오류는 FAILED다 — 호출측이 503으로 올릴 일이 아니다 */
    @Test
    void 가용성_외_오류는_FAILED로_구분한다() {
        stubOrg.failAt(0, Status.INTERNAL);

        MemberDirectory.Lookup lookup = directory.lookup(List.of(1L, 2L));

        assertThat(lookup.outcome()).isEqualTo(MemberDirectory.Outcome.FAILED);
        assertThat(lookup.members()).isEmpty();
    }

    /** 불능이 하나라도 있으면 FAILED보다 불능이 이긴다 — 다시 시도할 수 있는 실패가 더 중요한 신호다 */
    @Test
    void 불능과_실패가_섞이면_불능이_이긴다() {
        stubOrg.failAt(0, Status.INTERNAL);
        stubOrg.failAt(1, Status.UNAVAILABLE);

        MemberDirectory.Lookup lookup = directory.lookup(LongStream.rangeClosed(1, 300).boxed().toList());

        assertThat(lookup.outcome()).isEqualTo(MemberDirectory.Outcome.UNAVAILABLE);
    }

    /** 순서가 반대여도 마찬가지다 — 나중에 온 FAILED가 앞선 불능을 덮지 않는다 */
    @Test
    void 불능_다음에_실패가_와도_불능이_남는다() {
        stubOrg.failAt(0, Status.UNAVAILABLE);
        stubOrg.failAt(1, Status.INTERNAL);

        MemberDirectory.Lookup lookup = directory.lookup(LongStream.rangeClosed(1, 300).boxed().toList());

        assertThat(lookup.outcome()).isEqualTo(MemberDirectory.Outcome.UNAVAILABLE);
    }

    @Test
    void 빈_요청은_org를_부르지_않는다() {
        assertThat(directory.lookup(List.of()).ok()).isTrue();
        assertThat(directory.lookup(null).ok()).isTrue();
        assertThat(stubOrg.received).isEmpty();
    }

    /** 실패를 구분할 필요가 없는 호출측(메일 주소·이름)은 빈 결과만 본다 */
    @Test
    void members는_실패를_빈_결과로_준다() {
        stubOrg.failAt(0, Status.UNAVAILABLE);

        assertThat(directory.members(List.of(1L))).isEmpty();
    }
}
