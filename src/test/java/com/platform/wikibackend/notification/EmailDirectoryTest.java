package com.platform.wikibackend.notification;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.directory.FakeMemberDirectory;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.NotificationPrefRepository;
import com.platform.wikibackend.repository.NotificationRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import com.platform.wikibackend.security.AccountStatusInterceptor;
import org.junit.jupiter.api.AfterEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 알림 메일의 수신자 결정 — 주소의 정본은 org-service 디렉터리이고 개인 설정의 주소는 폴백이다.
 *
 * <p>스냅샷만 보던 때의 문제: org에서 이메일을 바꾸면 위키는 그 사람이 다시 다녀갈 때까지 옛 주소로
 * 계속 보냈고, 떠난 계정에도 계속 보냈다. 여기서 보는 것은 그 세 가지다 — 디렉터리 우선, 막힌 계정
 * 제외, org를 못 읽으면 스냅샷 폴백(메일 때문에 저장이 막히지는 않는다).
 */
@SpringBootTest
@ActiveProfiles("test")
class EmailDirectoryTest {

    static final long ALICE = 1L;
    static final long BOB = 2L;
    static final long CAROL = 3L;

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRevisionRepository revisions;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationPrefRepository prefs;
    @Autowired FakePermissionClient perms;
    @Autowired FakeMemberDirectory directory;
    @Autowired AccountStatusInterceptor gate;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired NotificationDigestService digest;
    @MockitoBean OrgMailClient orgMail;

    MockMvc mvc;
    Space space;

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
        directory.reset();
        gate.evictAll();
        space = spaces.save(Space.of("mail" + (System.nanoTime() % 100000), "메일", null, ALICE));
        for (long user : new long[] {ALICE, BOB, CAROL}) {
            perms.allow(user, space.getId(), WikiAction.VIEW);
            perms.allow(user, space.getId(), WikiAction.EDIT);
        }
    }

    @AfterEach
    void restore() {
        directory.reset();
        gate.evictAll();
    }

    /** 수신자가 알림함을 한 번 열어야 설정 행(스냅샷 주소)이 생긴다 */
    private void visitInbox(long userId, String name) throws Exception {
        mvc.perform(get("/api/wiki/notifications").with(asUser(userId, name))).andExpect(status().isOk());
    }

    private long createPage() throws Exception {
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(ALICE, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId()
                                + ",\"parentId\":null,\"title\":\"회의록\",\"content\":\"본문\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private void mention(String markdown, int expectedVersion) throws Exception {
        long id = createPage();
        mvc.perform(put("/api/wiki/pages/" + id).with(asUser(ALICE, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"회의록\",\"content\":\"" + markdown
                                + "\",\"expectedVersion\":" + expectedVersion + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void 디렉터리_주소가_스냅샷보다_우선한다() throws Exception {
        visitInbox(BOB, "Bob"); // 스냅샷은 bob@test.com
        directory.put(BOB, "Bob", "bob.new@org.example", "ACTIVE");

        mention("[@Bob](user:2) 확인 부탁", 1);

        ArgumentCaptor<List<String>> sent = ArgumentCaptor.captor();
        verify(orgMail, timeout(5000)).send(sent.capture(), any(), any());
        assertThat(sent.getValue()).containsExactly("bob.new@org.example");
    }

    /** 떠난 사람에게 계속 보내지 않는다 — 스냅샷이 남아 있어도 폴백하지 않는다 */
    @Test
    void 비활성_계정에는_보내지_않는다() throws Exception {
        visitInbox(BOB, "Bob");
        directory.put(BOB, "Bob", "bob@org.example", "DEACTIVATED");

        mention("[@Bob](user:2) 확인 부탁", 1);

        verify(orgMail, after(500).never()).send(anyList(), any(), any());
        assertThat(notifications.findByUserIdAndReadAtIsNull(BOB)).hasSize(1); // 알림함에는 남는다
    }

    /**
     * 정지된 계정도 마찬가지다. 제목에 문서 제목이 그대로 실리는데, 그 사람은 계정 상태 게이트에
     * 막혀 그 문서를 열 수도 없다 — 열지 못하는 문서의 제목을 메일로 흘리지 않는다.
     */
    @Test
    void 정지된_계정에도_보내지_않는다() throws Exception {
        visitInbox(BOB, "Bob");
        directory.put(BOB, "Bob", "bob@org.example", "SUSPENDED");

        mention("[@Bob](user:2) 확인 부탁", 1);

        verify(orgMail, after(500).never()).send(anyList(), any(), any());
    }

    /** org 불능은 메일을 멈출 이유가 아니다 — 알던 주소로 보낸다 */
    @Test
    void 디렉터리가_불능이면_스냅샷으로_보낸다() throws Exception {
        visitInbox(BOB, "Bob");
        // 편집자(Alice)의 계정 상태를 먼저 캐시에 올린다 — 그러지 않으면 불능이 게이트에 먼저 걸려
        // 저장 자체가 503이 되고, 검증하려는 메일 경로까지 가지 못한다(게이트는 30초 캐시).
        directory.put(ALICE, "Alice", "alice@org.example", "ACTIVE");
        visitInbox(ALICE, "Alice");
        directory.setUnavailable(true);

        mention("[@Bob](user:2) 확인 부탁", 1);

        ArgumentCaptor<List<String>> sent = ArgumentCaptor.captor();
        verify(orgMail, timeout(5000)).send(sent.capture(), any(), any());
        assertThat(sent.getValue()).containsExactly("bob@test.com");
    }

    /** org가 이메일을 비워 둔 사람도 스냅샷으로 간다 — "사람은 있는데 주소를 모른다"는 거부가 아니다 */
    @Test
    void 디렉터리에_주소가_없으면_스냅샷으로_보낸다() throws Exception {
        visitInbox(BOB, "Bob");
        directory.put(BOB, "Bob", "", "ACTIVE");

        mention("[@Bob](user:2) 확인 부탁", 1);

        ArgumentCaptor<List<String>> sent = ArgumentCaptor.captor();
        verify(orgMail, timeout(5000)).send(sent.capture(), any(), any());
        assertThat(sent.getValue()).containsExactly("bob@test.com");
    }

    /** 요약 메일도 같은 규칙을 탄다 — 발송 직전에 주소를 정하고 막힌 계정은 건너뛴다 */
    @Test
    void 요약_메일도_막힌_계정에는_가지_않는다() throws Exception {
        visitInbox(BOB, "Bob");
        mvc.perform(put("/api/wiki/notifications/prefs").with(asUser(BOB, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailEnabled\":true,\"emailMode\":\"DAILY\",\"mentioned\":true,"
                                + "\"pageUpdated\":true,\"comment\":true,\"shared\":true}"))
                .andExpect(status().isOk());
        mention("[@Bob](user:2) 확인 부탁", 1);
        directory.put(BOB, "Bob", "bob@org.example", "DEACTIVATED");

        assertThat(digest.run()).isEqualTo(1); // 요약을 만들기는 한다 — 주소 판정은 발송 직전이다
        verify(orgMail, after(500).never()).send(anyList(), any(), any());
    }

    /**
     * 스냅샷이 없어도 org가 주소를 알면 요약이 나간다. 예전에는 설정 행의 주소로 대상을 걸러서,
     * 원장에 주소가 있는 사람도 위키에 흔적이 없으면 받지 못했다.
     */
    @Test
    void 스냅샷이_없어도_원장_주소로_요약을_보낸다() throws Exception {
        // 토큰에 email이 없는 사용자 — 설정 행은 생기지만 주소는 비어 있다
        mvc.perform(get("/api/wiki/notifications")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .jwt().jwt(j -> j.subject(String.valueOf(BOB)).claim("name", "Bob"))))
                .andExpect(status().isOk());
        mvc.perform(put("/api/wiki/notifications/prefs").with(asUser(BOB, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailEnabled\":true,\"emailMode\":\"DAILY\",\"mentioned\":true,"
                                + "\"pageUpdated\":true,\"comment\":true,\"shared\":true}"))
                .andExpect(status().isOk());
        prefs.findById(BOB).ifPresent(pref -> jdbc.update(
                "update notification_pref set email = null where user_id = ?", BOB));
        mention("[@Bob](user:2) 확인 부탁", 1);
        directory.put(BOB, "Bob", "bob@org.example", "ACTIVE");

        assertThat(digest.run()).isEqualTo(1);
        ArgumentCaptor<List<String>> sent = ArgumentCaptor.captor();
        verify(orgMail, timeout(5000)).send(sent.capture(), any(), any());
        assertThat(sent.getValue()).containsExactly("bob@org.example");
    }

    /**
     * 한 트랜잭션에서 생긴 알림은 묶어서 디렉터리를 <b>한 번</b>만 읽는다. 사람마다 물으면
     * 워처가 열 명인 문서를 고칠 때 왕복도 열 번이 된다.
     */
    @Test
    void 한_트랜잭션의_수신자들을_한_번에_묻는다() throws Exception {
        visitInbox(BOB, "Bob");
        visitInbox(CAROL, "Carol");
        directory.reset(); // 알림함 방문이 만든 게이트 조회는 세지 않는다
        gate.evictAll();
        directory.put(BOB, "Bob", "bob@org.example", "ACTIVE");
        directory.put(CAROL, "Carol", "carol@org.example", "ACTIVE");

        mention("[@Bob](user:2) [@Carol](user:3) 확인 부탁", 1);

        verify(orgMail, timeout(5000).times(2)).send(anyList(), any(), any());
        // 게이트가 Alice를 한 번 묻고, 메일 발송이 Bob·Carol을 한 번에 묻는다 — 수신자당 왕복이 아니다
        assertThat(directory.calls()).anyMatch(ids -> ids.size() == 2 && ids.containsAll(java.util.List.of(BOB, CAROL)));
        assertThat(directory.calls().stream().filter(ids -> ids.size() > 1)).hasSize(1);
    }
}
