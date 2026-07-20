package com.platform.wikibackend.event;

import com.platform.proto.events.v1.EventEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class EventRelayTest {

    @Autowired EventRelay relay;
    @Autowired RecordingEventPublisher recording;
    @Autowired org.springframework.transaction.PlatformTransactionManager txm;
    TransactionTemplate tx; // TransactionTemplate은 자동구성 빈이 아님 — 직접 생성

    @BeforeEach
    void reset() {
        tx = new TransactionTemplate(txm);
        recording.reset();
    }

    private static EventEnvelope sample() {
        return EventEnvelope.newBuilder().setEventId("e-1").setActorId(1L).setSource("wiki-backend").build();
    }

    @Test
    void 트랜잭션_안에서는_커밋_후에_발행된다() {
        tx.executeWithoutResult(status -> {
            relay.afterCommit(sample());
            assertThat(recording.events).isEmpty(); // 아직 커밋 전
        });
        assertThat(recording.events).hasSize(1);
    }

    @Test
    void 롤백되면_발행되지_않는다() {
        tx.executeWithoutResult(status -> {
            relay.afterCommit(sample());
            status.setRollbackOnly();
        });
        assertThat(recording.events).isEmpty();
    }

    @Test
    void 비트랜잭션_문맥에서는_즉시_발행된다() {
        relay.afterCommit(sample());
        assertThat(recording.events).hasSize(1);
    }
}
