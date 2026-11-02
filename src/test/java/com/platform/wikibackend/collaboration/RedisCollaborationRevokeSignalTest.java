package com.platform.wikibackend.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RedisCollaborationRevokeSignalTest {

    ObjectMapper json = new ObjectMapper();

    @Test
    void 재확인_신호는_계약_채널에_rooms_배열_JSON으로_발행한다() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        Clock clock = Clock.fixed(Instant.parse("2026-11-02T00:00:10Z"), ZoneOffset.UTC);
        new RedisCollaborationRevokeSignal(redis, json, clock).publish(List.of("page:7", "page:8"));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq("wiki:collaboration:revoke"), message.capture());
        JsonNode sent = json.readTree(message.getValue());
        assertThat(sent.size()).isEqualTo(2);
        // collaboration-service가 티켓 issuedAt(같은 시계)과 비교해 회수 전 티켓으로 늦게 붙은 연결을 닫는다
        assertThat(sent.path("revokedAt").asText()).isEqualTo("2026-11-02T00:00:10Z");
        assertThat(sent.path("rooms").isArray()).isTrue();
        assertThat(sent.path("rooms").get(0).asText()).isEqualTo("page:7");
        assertThat(sent.path("rooms").get(1).asText()).isEqualTo("page:8");
        assertThat(CollaborationRevokeSignal.CHANNEL).isEqualTo("wiki:collaboration:revoke");
    }

    @Test
    void 큰_서브트리는_메시지당_상한으로_나눠_보낸다() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        List<String> rooms = IntStream.rangeClosed(1, RedisCollaborationRevokeSignal.ROOMS_PER_MESSAGE + 1)
                .mapToObj(i -> "page:" + i).toList();

        new RedisCollaborationRevokeSignal(redis, json).publish(rooms);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(redis, times(2)).convertAndSend(eq("wiki:collaboration:revoke"), message.capture());
        assertThat(json.readTree(message.getAllValues().get(0)).path("rooms").size())
                .isEqualTo(RedisCollaborationRevokeSignal.ROOMS_PER_MESSAGE);
        assertThat(json.readTree(message.getAllValues().get(1)).path("rooms").size()).isEqualTo(1);
    }

    @Test
    void Redis가_죽어도_이미_커밋된_변경을_실패시키지_않는다() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doThrow(new RedisConnectionFailureException("down"))
                .when(redis).convertAndSend(anyString(), anyString());

        assertThatCode(() -> new RedisCollaborationRevokeSignal(redis, json).publish(List.of("page:7")))
                .doesNotThrowAnyException();
    }
}
