package com.platform.wikibackend.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.wikibackend.common.ForbiddenException;
import com.platform.wikibackend.common.ServiceUnavailableException;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.page.PageService;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.space.SpaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CollaborationTicketServiceTest {

    @Mock PageService pages;
    @Mock SpaceService spaces;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    @Mock SecureRandom random;

    ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    Clock clock = Clock.fixed(Instant.parse("2026-08-16T12:00:00Z"), ZoneOffset.UTC);
    CollaborationTicketService service;

    @BeforeEach
    void setup() {
        service = new CollaborationTicketService(
                pages, spaces, redis, json, Duration.ofMinutes(1), clock, random);
    }

    private void stubTicketStorage() {
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(invocation -> {
            byte[] target = invocation.getArgument(0);
            for (int i = 0; i < target.length; i++) target[i] = (byte) (i + 1);
            return null;
        }).when(random).nextBytes(any(byte[].class));
    }

    @Test
    void EDIT_권한을_확인하고_원문이_아닌_SHA256_key에_60초_payload를_저장한다() throws Exception {
        stubTicketStorage();
        // W18: 권한(스페이스 EDIT + 페이지 제한)은 PageService.getEditable이 한 번에 판정한다
        when(pages.getEditable(42L, 7L)).thenReturn(Page.of(3L, null, "문서", "본문", 1L));

        CollaborationTicketResponse response = service.issue(42L, " Alice\nKim ", 7L);

        verify(pages).getEditable(42L, 7L);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(values).set(key.capture(), payload.capture(), ttl.capture());

        assertThat(response.ticket()).matches("[A-Za-z0-9_-]{43}");
        assertThat(response.room()).isEqualTo("page:7");
        assertThat(response.websocketPath()).isEqualTo("/api/wiki/collaboration");
        assertThat(response.expiresAt()).isEqualTo(Instant.parse("2026-08-16T12:01:00Z"));
        assertThat(key.getValue()).isEqualTo(
                CollaborationTicketService.KEY_PREFIX + CollaborationTicketService.sha256(response.ticket()));
        assertThat(key.getValue()).doesNotContain(response.ticket());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofMinutes(1));

        JsonNode stored = json.readTree(payload.getValue());
        assertThat(stored.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(stored.path("pageId").asLong()).isEqualTo(7L);
        assertThat(stored.path("userId").asLong()).isEqualTo(42L);
        assertThat(stored.path("displayName").asText()).isEqualTo("Alice Kim");
        assertThat(stored.path("room").asText()).isEqualTo("page:7");
        assertThat(stored.path("permission").asText()).isEqualTo("EDIT");
        assertThat(payload.getValue()).doesNotContain(response.ticket());
    }

    @Test
    void EDIT_권한이_없으면_ticket을_저장하지_않는다() {
        // 스페이스 EDIT 부재든 페이지 제한이든 getEditable이 Forbidden을 던진다(W18 단일 판정)
        doThrow(new ForbiddenException("EDIT 권한 필요"))
                .when(pages).getEditable(9L, 7L);

        assertThatThrownBy(() -> service.issue(9L, "Viewer", 7L))
                .isInstanceOf(ForbiddenException.class);

        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void Redis_불능이면_사용할_수_없는_ticket을_발급하지_않고_503으로_실패한다() {
        stubTicketStorage();
        when(pages.getEditable(42L, 7L)).thenReturn(Page.of(3L, null, "문서", "본문", 1L));
        doThrow(new RedisConnectionFailureException("down"))
                .when(values).set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() -> service.issue(42L, "Alice", 7L))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("공동 편집 세션을 시작할 수 없습니다");
    }

    @Test
    void TTL이_5분을_넘거나_0이면_구성_오류로_거부한다() {
        assertThatThrownBy(() -> new CollaborationTicketService(
                pages, spaces, redis, json, Duration.ZERO, clock, random))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CollaborationTicketService(
                pages, spaces, redis, json, Duration.ofMinutes(6), clock, random))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
