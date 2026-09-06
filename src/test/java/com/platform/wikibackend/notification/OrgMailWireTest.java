package com.platform.wikibackend.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.NotificationPrefRepository;
import com.platform.wikibackend.repository.NotificationRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 알림 한 건이 실제로 HTTP를 타고 메일 허브까지 가는지 — 배선 검증.
 *
 * <p>{@link EmailNotificationTest}는 허브 클라이언트를 목으로 세워 "무엇을 넘기는가"를 보고,
 * {@link OrgMailClientTest}는 클라이언트 혼자 계약을 지키는지 본다. 그 둘이 모두 초록이어도
 * 빈이 잘못 물리면 아무것도 나가지 않으므로, 여기서는 진짜 클라이언트를 가짜 org에 붙여
 * 커밋 뒤 발송까지 한 번 관통시킨다.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrgMailWireTest {

    static final long ALICE = 1L;
    static final long BOB = 2L;
    static final String TOKEN = "wire-secret";

    static final List<String> sent = new CopyOnWriteArrayList<>();
    static final HttpServer ORG = fakeOrg();

    static HttpServer fakeOrg() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/org/mail", exchange -> {
                String body = "{\"enabled\":true}";
                if (!exchange.getRequestURI().getPath().endsWith("/status")) {
                    sent.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    body = "{\"accepted\":1,\"disabled\":false}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void orgMail(DynamicPropertyRegistry registry) {
        registry.add("platform.wiki.org.internal-uri", () -> "http://127.0.0.1:" + ORG.getAddress().getPort());
        registry.add("platform.wiki.org.internal-token", () -> TOKEN);
    }

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRevisionRepository revisions;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationPrefRepository prefs;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    final ObjectMapper json = new ObjectMapper();
    MockMvc mvc;
    Space space;

    @BeforeEach
    void setup() {
        sent.clear();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        notifications.deleteAll();
        prefs.deleteAll();
        revisions.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        space = spaces.save(Space.of("wire", "배선", null, ALICE));
        for (long user : new long[] {ALICE, BOB}) {
            perms.allow(user, space.getId(), WikiAction.VIEW);
            perms.allow(user, space.getId(), WikiAction.EDIT);
        }
    }

    @Test
    void 멘션_한_건이_허브까지_간다() throws Exception {
        mvc.perform(get("/api/wiki/notifications").with(asUser(BOB, "Bob"))).andExpect(status().isOk());

        String created = mvc.perform(post("/api/wiki/pages").with(asUser(ALICE, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId()
                                + ",\"parentId\":null,\"title\":\"배포 절차\",\"content\":\"본문\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mvc.perform(put("/api/wiki/pages/" + id).with(asUser(ALICE, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"배포 절차\",\"content\":\"[@Bob](user:2) 확인 부탁\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());

        String body = awaitOneMail();
        JsonNode node = json.readTree(body);
        assertThat(node.get("to").get(0).asText()).isEqualTo("bob@test.com");
        assertThat(node.get("subject").asText()).contains("Alice").contains("배포 절차");
        assertThat(node.get("text").asText()).contains("/spaces/" + space.getId() + "/pages/" + id);
        assertThat(node.get("source").asText()).isEqualTo("wiki");
    }

    /** 설정 화면의 "메일 켜짐" 표시도 허브 상태에서 온다 */
    @Test
    void 설정_화면은_허브_상태를_그대로_말한다() throws Exception {
        mvc.perform(get("/api/wiki/notifications/prefs").with(asUser(BOB, "Bob")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.emailConfigured").value(true));
    }

    private String awaitOneMail() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (!sent.isEmpty()) return sent.get(0);
            Thread.sleep(25);
        }
        throw new AssertionError("허브에 아무것도 도착하지 않았다");
    }
}
