package com.platform.wikibackend.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RedisCollaborationResetSignalTest {

    ObjectMapper json = new ObjectMapper();

    @Test
    void 리셋_신호는_계약_채널에_room과_resetEpoch_JSON으로_발행한다() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        new RedisCollaborationResetSignal(redis, json).publish("page:7", 3L);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq("wiki:collaboration:reset"), message.capture());
        JsonNode sent = json.readTree(message.getValue());
        assertThat(sent.size()).isEqualTo(2);
        assertThat(sent.path("room").asText()).isEqualTo("page:7");
        // collaboration-service가 정수로 비교한다 — 문자열이면 세대 비교가 조용히 틀린다
        assertThat(sent.path("resetEpoch").isIntegralNumber()).isTrue();
        assertThat(sent.path("resetEpoch").asLong()).isEqualTo(3L);
        // 게시 세대(generation)는 싣지 않는다 — 수신 쪽이 잘못된 카운터로 비교하지 못하게(C-1)
        assertThat(sent.has("generation")).isFalse();
        assertThat(CollaborationResetSignal.CHANNEL).isEqualTo("wiki:collaboration:reset");
    }

    @Test
    void Redis가_죽어도_이미_커밋된_저장을_실패시키지_않는다() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doThrow(new RedisConnectionFailureException("down"))
                .when(redis).convertAndSend(anyString(), anyString());

        assertThatCode(() -> new RedisCollaborationResetSignal(redis, json).publish("page:7", 3L))
                .doesNotThrowAnyException();
    }
}
