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
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 이메일 알림 채널(W23) — 설정 저장·주소 스냅샷·발송/미발송.
 * host를 준 별도 컨텍스트다: 기본 컨텍스트(host 비어 있음)에서는 채널이 꺼져 있어야 한다.
 */
@SpringBootTest(properties = "spring.mail.host=smtp.test")
@ActiveProfiles("test")
class EmailNotificationTest {

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRevisionRepository revisions;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationPrefRepository prefs;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean JavaMailSender mailSender;
    @Autowired NotificationDigestService digest;
    MockMvc mvc;

    Space space;
    static final long ALICE = 1L;
    static final long BOB = 2L;

    @BeforeEach
    void setup() {
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

    @Test
    void 알림함을_연_사용자는_설정을_손대지_않아도_멘션_메일을_받는다() throws Exception {
        mvc.perform(get("/api/wiki/notifications").with(asUser(BOB, "Bob"))).andExpect(status().isOk());

        mentionBob();

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, timeout(5000)).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("bob@test.com");
        assertThat(sent.getValue().getSubject()).contains("Alice").contains("회의록").contains("멘션");
        assertThat(sent.getValue().getText()).contains("/spaces/" + space.getId() + "/pages/");
    }

    @Test
    void 이메일을_끈_사용자에게는_가지_않는다() throws Exception {
        mvc.perform(put("/api/wiki/notifications/prefs").with(asUser(BOB, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailEnabled\":false,\"mentioned\":true,\"pageUpdated\":true,\"comment\":true,\"shared\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailEnabled").value(false));

        mentionBob();

        verify(mailSender, after(500).never()).send(any(SimpleMailMessage.class));
        assertThat(notifications.findByUserIdAndReadAtIsNull(BOB)).hasSize(1); // 알림함에는 남는다
    }

    @Test
    void 타입만_끄면_그_타입_메일만_빠진다() throws Exception {
        mvc.perform(put("/api/wiki/notifications/prefs").with(asUser(BOB, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailEnabled\":true,\"mentioned\":false,\"pageUpdated\":true,\"comment\":true,\"shared\":true}"))
                .andExpect(status().isOk());

        mentionBob();

        verify(mailSender, after(500).never()).send(any(SimpleMailMessage.class));
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
        verify(mailSender, after(500).never()).send(any(SimpleMailMessage.class));

        assertThat(digest.run()).isEqualTo(1);
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, timeout(5000)).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("bob@test.com");
        assertThat(sent.getValue().getSubject()).contains("요약").contains("2건");
        assertThat(sent.getValue().getText()).contains("나를 멘션").contains("회의록");

        // 같은 알림은 다음 요약에 다시 들어가지 않는다
        assertThat(digest.run()).isZero();
        verify(mailSender, after(500).times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void 주소를_모르는_사용자에게는_보낼_수_없다() throws Exception {
        mentionBob(); // Bob은 한 번도 다녀가지 않았다

        verify(mailSender, after(500).never()).send(any(SimpleMailMessage.class));
    }
}
