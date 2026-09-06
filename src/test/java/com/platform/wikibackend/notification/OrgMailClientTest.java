package com.platform.wikibackend.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 메일 허브(org-service) 계약 — 위키가 실제로 무엇을 실어 보내고, 허브가 답하지 않을 때 무엇을 하는가.
 *
 * <p>가짜 org를 JDK {@link HttpServer}로 세운다. 계약이 문서로만 맞춰져 있으면(허브 구현은 다른 레인이
 * 동시에 만든다) 필드 이름 하나가 어긋나도 양쪽 테스트가 모두 초록이다 — 여기서는 바이트로 확인한다.
 */
class OrgMailClientTest {

    static final String TOKEN = "shared-secret";

    HttpServer server;
    String baseUri;
    final ObjectMapper json = new ObjectMapper();

    final List<Recorded> sendCalls = new CopyOnWriteArrayList<>();
    final AtomicInteger statusCalls = new AtomicInteger();

    volatile int sendStatus = 202;
    volatile String sendBody = "{\"accepted\":1,\"disabled\":false}";
    volatile int statusStatus = 200;
    volatile String statusBody = "{\"enabled\":true}";

    @BeforeEach
    void startFakeOrg() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/org/mail", exchange -> {
            // 같은 접두사라 /status가 여기로도 들어온다 — 경로로 갈라 준다
            if (exchange.getRequestURI().getPath().endsWith("/status")) {
                statusCalls.incrementAndGet();
                respond(exchange, statusStatus, statusBody);
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            sendCalls.add(new Recorded(exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-Internal-Token"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    body));
            respond(exchange, sendStatus, sendBody);
        });
        server.start();
        baseUri = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopFakeOrg() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private OrgMailClient client() {
        return new OrgMailClient(baseUri, TOKEN, json);
    }

    @Test
    void 발송은_계약대로_넘긴다() throws Exception {
        assertThat(client().send(List.of("bob@org.example"), "[Wiki] 제목", "본문")).isTrue();

        assertThat(sendCalls).hasSize(1);
        Recorded call = sendCalls.get(0);
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.path()).isEqualTo("/internal/org/mail");
        assertThat(call.token()).isEqualTo(TOKEN);
        assertThat(call.contentType()).contains("application/json");
        JsonNode body = json.readTree(call.body());
        assertThat(body.get("to")).hasSize(1);
        assertThat(body.get("to").get(0).asText()).isEqualTo("bob@org.example");
        assertThat(body.get("subject").asText()).isEqualTo("[Wiki] 제목");
        assertThat(body.get("text").asText()).isEqualTo("본문");
        assertThat(body.get("source").asText()).isEqualTo("wiki");
    }

    /** 관리 화면에서 끈 상태는 장애가 아니다 — 조용히 건너뛴다. */
    @Test
    void 허브가_꺼져_있으면_건너뛰고_상태_캐시에도_반영한다() {
        sendBody = "{\"accepted\":0,\"disabled\":true}";
        OrgMailClient client = client();

        assertThat(client.send(List.of("bob@org.example"), "제목", "본문")).isFalse();

        assertThat(sendCalls).hasSize(1);
        assertThat(client.enabled()).isFalse();
        assertThat(statusCalls).hasValue(0); // 방금 "꺼짐"을 들었으니 다시 묻지 않는다
    }

    /** 허브 오류로 문서 저장이 깨지면 안 된다 — 예외 없이 false다. */
    @Test
    void 허브가_오류를_주면_삼킨다() {
        sendStatus = 500;
        sendBody = "{\"error\":\"내부 오류\"}";

        assertThat(client().send(List.of("bob@org.example"), "제목", "본문")).isFalse();
        assertThat(sendCalls).hasSize(1);
    }

    @Test
    void 허브에_닿지_못하면_삼킨다() {
        OrgMailClient client = new OrgMailClient("http://127.0.0.1:1", TOKEN, json);

        assertThat(client.send(List.of("bob@org.example"), "제목", "본문")).isFalse();
        assertThat(client.enabled()).isFalse();
    }

    @Test
    void 상태는_한_번만_묻고_캐시한다() {
        OrgMailClient client = client();

        assertThat(client.enabled()).isTrue();
        assertThat(client.enabled()).isTrue();
        assertThat(client.enabled()).isTrue();

        assertThat(statusCalls).hasValue(1);
    }

    /** 상태를 못 읽으면 꺼진 것으로 본다 — 설정 화면이 "온다"고 말해 놓고 오지 않는 편이 나쁘다. */
    @Test
    void 상태_조회가_실패하면_꺼진_것으로_본다() {
        statusStatus = 503;
        statusBody = "{\"error\":\"메일 서비스가 응답하지 않습니다\"}";

        assertThat(client().enabled()).isFalse();
        assertThat(statusCalls).hasValue(1);
    }

    @Test
    void 허브가_꺼져_있다고_답하면_꺼진_것이다() {
        statusBody = "{\"enabled\":false}";

        assertThat(client().enabled()).isFalse();
    }

    /** env를 안 넣은 배포는 채널이 없는 것이다 — 매 알림마다 403 왕복을 버리지 않는다. */
    @Test
    void 토큰이_없으면_부르지도_않는다() {
        OrgMailClient client = new OrgMailClient(baseUri, "", json);

        assertThat(client.enabled()).isFalse();
        assertThat(client.send(List.of("bob@org.example"), "제목", "본문")).isFalse();
        assertThat(statusCalls).hasValue(0);
        assertThat(sendCalls).isEmpty();
    }

    /**
     * 허브는 한 요청의 수신자를 100명까지만 받고 넘으면 400이다. 잘라 보내면 101번째 사람만 조용히
     * 알림을 못 받는다 — 나눠 보내고, 아무도 빠지지 않는지 본다.
     */
    @Test
    void 수신자가_백_명을_넘으면_나눠_보낸다() throws Exception {
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 250; i++) many.add("user" + i + "@org.example");

        assertThat(client().send(many, "제목", "본문")).isTrue();

        assertThat(sendCalls).hasSize(3);
        List<String> delivered = new java.util.ArrayList<>();
        for (Recorded call : sendCalls) {
            JsonNode to = json.readTree(call.body()).get("to");
            assertThat(to.size()).isLessThanOrEqualTo(100);
            to.forEach(node -> delivered.add(node.asText()));
        }
        assertThat(delivered).containsExactlyElementsOf(many);
    }

    /** 한 묶음이라도 거절당하면 "보냈다"고 말하지 않는다 — 나머지는 그대로 보낸다. */
    @Test
    void 나눠_보내다_한_묶음이_실패하면_false다() {
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 150; i++) many.add("user" + i + "@org.example");
        sendStatus = 500;

        assertThat(client().send(many, "제목", "본문")).isFalse();
        assertThat(sendCalls).hasSize(2);
    }

    /** 꺼진 허브에 남은 묶음까지 던지지 않는다 — 같은 답이 돌아온다. */
    @Test
    void 첫_묶음이_꺼짐이면_나머지는_보내지_않는다() {
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 150; i++) many.add("user" + i + "@org.example");
        sendBody = "{\"accepted\":0,\"disabled\":true}";

        assertThat(client().send(many, "제목", "본문")).isFalse();
        assertThat(sendCalls).hasSize(1);
    }

    @Test
    void 같은_주소는_한_번만_싣는다() throws Exception {
        assertThat(client().send(List.of("bob@org.example", " bob@org.example ", "carol@org.example"),
                "제목", "본문")).isTrue();

        JsonNode to = json.readTree(sendCalls.get(0).body()).get("to");
        assertThat(to).hasSize(2);
        assertThat(to.get(0).asText()).isEqualTo("bob@org.example");
        assertThat(to.get(1).asText()).isEqualTo("carol@org.example");
    }

    @Test
    void 주소가_비면_보내지_않는다() {
        OrgMailClient client = client();

        assertThat(client.send(List.of(), "제목", "본문")).isFalse();
        assertThat(client.send(List.of("", "   "), "제목", "본문")).isFalse();

        assertThat(sendCalls).isEmpty();
    }

    /** 끝의 슬래시가 붙은 URI로도 경로가 겹치지 않는다 */
    @Test
    void 기본_주소의_끝_슬래시를_다듬는다() {
        OrgMailClient client = new OrgMailClient(baseUri + "/", TOKEN, json);

        assertThat(client.send(List.of("bob@org.example"), "제목", "본문")).isTrue();
        assertThat(sendCalls.get(0).path()).isEqualTo("/internal/org/mail");
    }

    record Recorded(String method, String path, String token, String contentType, String body) {
    }
}
