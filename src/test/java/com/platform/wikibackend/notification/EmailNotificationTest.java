package com.platform.wikibackend.notification;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.NotificationPrefRepository;
import com.platform.wikibackend.repository.NotificationRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 이메일 알림 채널(W23) — 설정 저장·주소 스냅샷·발송/미발송.
 *
 * 발송기는 org-service 메일 허브다(M4). 여기서는 허브를 세워 두고 "무엇을 넘기는가"만 본다 —
 * 허브 계약(본문·헤더·꺼짐 응답)은 {@link OrgMailClientTest}가, 실제 HTTP 왕복은
 * {@link OrgMailWireTest}가 본다. 기본 컨텍스트(허브 토큰 없음)에서는 채널이 꺼져 있어야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class EmailNotificationTest {

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRevisionRepository revisions;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationPrefRepository prefs;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean OrgMailClient orgMail;
    @Autowired NotificationDigestService digest;
    MockMvc mvc;

    Space space;
    static final long ALICE = 1L;
    static final long BOB = 2L;

    @BeforeEach
    void setup() {
        given(orgMail.enabled()).willReturn(true);
        given(orgMail.send(anyList(), any(), any())).willReturn(true);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        notifications.deleteAll();
        prefs.deleteAll();
        revisions.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        space = spaces.save(Space.of("dev", "개발", null, ALICE));
        for (long u : new long[] {ALICE, BOB}) {
            perms.allow(u, space.getId(), WikiAction.VIEW);
            perms.allow(u, space.getId(), WikiAction.EDIT);
        }
    }

    /** 생성은 알림 트리거가 아니다(V11 재량) — 만든 뒤 멘션을 넣는 업데이트로 MENTIONED를 낸다. */
    private void mentionBob() throws Exception {
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(ALICE, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId()
                                + ",\"parentId\":null,\"title\":\"회의록\",\"content\":\"본문\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mvc.perform(put("/api/wiki/pages/" + id).with(asUser(ALICE, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"회의록\",\"content\":\"[@Bob](user:2) 확인 부탁\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());
    }

    @Test
    void 설정을_열면_토큰의_주소가_남고_기본값은_모두_켜짐이다() throws Exception {
        mvc.perform(get("/api/wiki/notifications/prefs").with(asUser(BOB, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailConfigured").value(true))
                .andExpect(jsonPath("$.email").value("bob@test.com"))
                .andExpect(jsonPath("$.emailEnabled").value(true))
                .andExpect(jsonPath("$.mentioned").value(true));
    }

    /** 허브가 꺼져 있으면 화면이 먼저 그 사실을 말한다 — 스위치만 켜고 기다리게 두지 않는다. */
    @Test
    void 허브가_꺼져_있으면_설정_화면이_먼저_알린다() throws Exception {
        given(orgMail.enabled()).willReturn(false);

        mvc.perform(get("/api/wiki/notifications/prefs").with(asUser(BOB, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailConfigured").value(false));
    }

    @Test
    void 알림함을_연_사용자는_설정을_손대지_않아도_멘션_메일을_받는다() throws Exception {
        mvc.perform(get("/api/wiki/notifications").with(asUser(BOB, "Bob"))).andExpect(status().isOk());

        mentionBob();

        ArgumentCaptor<List<String>> to = ArgumentCaptor.captor();
        ArgumentCaptor<String> subject = ArgumentCaptor.captor();
        ArgumentCaptor<String> text = ArgumentCaptor.captor();
        verify(orgMail, timeout(5000)).send(to.capture(), subject.capture(), text.capture());
        assertThat(to.getValue()).containsExactly("bob@test.com");
        assertThat(subject.getValue()).contains("Alice").contains("회의록").contains("멘션");
        assertThat(text.getValue()).contains("/spaces/" + space.getId() + "/pages/");
    }

    /** 허브가 꺼져 있으면 원장도 읽지 않고 아무것도 넘기지 않는다 — 알림함만 남는다. */
    @Test
    void 허브가_꺼져_있으면_넘기지_않는다() throws Exception {
        given(orgMail.enabled()).willReturn(false);
        mvc.perform(get("/api/wiki/notifications").with(asUser(BOB, "Bob"))).andExpect(status().isOk());

        mentionBob();

        verify(orgMail, after(500).never()).send(anyList(), any(), any());
        assertThat(notifications.findByUserIdAndReadAtIsNull(BOB)).hasSize(1);
    }

    @Test
    void 이메일을_끈_사용자에게는_가지_않는다() throws Exception {
        mvc.perform(put("/api/wiki/notifications/prefs").with(asUser(BOB, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailEnabled\":false,\"mentioned\":true,\"pageUpdated\":true,\"comment\":true,\"shared\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailEnabled").value(false));

        mentionBob();

        verify(orgMail, after(500).never()).send(anyList(), any(), any());
        assertThat(notifications.findByUserIdAndReadAtIsNull(BOB)).hasSize(1); // 알림함에는 남는다
    }

    @Test
    void 타입만_끄면_그_타입_메일만_빠진다() throws Exception {
        mvc.perform(put("/api/wiki/notifications/prefs").with(asUser(BOB, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailEnabled\":true,\"mentioned\":false,\"pageUpdated\":true,\"comment\":true,\"shared\":true}"))
                .andExpect(status().isOk());

        mentionBob();

        verify(orgMail, after(500).never()).send(anyList(), any(), any());
    }

    @Test
    void 요약_모드는_바로_보내지_않고_하루_요약_한_통에_모은다() throws Exception {
        mvc.perform(put("/api/wiki/notifications/prefs").with(asUser(BOB, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailEnabled\":true,\"emailMode\":\"DAILY\",\"mentioned\":true,\"pageUpdated\":true,\"comment\":true,\"shared\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailMode").value("DAILY"));

        mentionBob();
        mentionBob();
        verify(orgMail, after(500).never()).send(anyList(), any(), any());

        assertThat(digest.run()).isEqualTo(1);
        ArgumentCaptor<List<String>> to = ArgumentCaptor.captor();
        ArgumentCaptor<String> subject = ArgumentCaptor.captor();
        ArgumentCaptor<String> text = ArgumentCaptor.captor();
        verify(orgMail, timeout(5000)).send(to.capture(), subject.capture(), text.capture());
        assertThat(to.getValue()).containsExactly("bob@test.com");
        assertThat(subject.getValue()).contains("요약").contains("2건");
        assertThat(text.getValue()).contains("나를 멘션").contains("회의록");

        // 같은 알림은 다음 요약에 다시 들어가지 않는다
        assertThat(digest.run()).isZero();
        verify(orgMail, after(500).times(1)).send(anyList(), any(), any());
    }

    @Test
    void 주소를_모르는_사용자에게는_보낼_수_없다() throws Exception {
        mentionBob(); // Bob은 한 번도 다녀가지 않았다

        verify(orgMail, after(500).never()).send(anyList(), any(), any());
    }
}
