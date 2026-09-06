package com.platform.wikibackend.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 플랫폼 메일 허브(org-service) 내부 API 클라이언트.
 *
 * <p>위키는 더 이상 SMTP를 직접 말하지 않는다. 설정·자격증명·재시도·발송 로그는 org-service 한 곳에
 * 모이고(2026-09-07 플랫폼 메일 설계 §3), 위키가 하는 일은 "누구에게 어떤 제목과 본문을"까지다 —
 * 보내는 주소·서버·TLS는 관리 화면이 정한다.
 *
 * <p>계약은 둘뿐이다.
 * <ul>
 *   <li>{@code POST /internal/org/mail} {@code {to[], subject, text, html?, source}} → 202
 *       {@code {accepted, disabled}}</li>
 *   <li>{@code GET /internal/org/mail/status} → {@code {enabled}}</li>
 * </ul>
 * 둘 다 사용자 JWT가 아니라 공유 비밀 {@code X-Internal-Token}으로 인증한다.
 *
 * <p><b>실패는 삼킨다.</b> 메일은 알림함의 사본이지 원본이 아니다 — 허브가 죽었다고 문서 저장이
 * 실패하거나 알림함이 비어서는 안 된다. 모든 실패는 warn 로그와 {@code false}로 끝난다.
 *
 * <p>토큰이 비어 있으면 아예 호출하지 않는다. org 쪽 {@code InternalTokenFilter}가 어차피 403을
 * 주므로, env를 안 넣은 배포가 매 알림마다 왕복 한 번을 버리지 않게 한다 — 그 배포에서 메일 채널은
 * 그냥 꺼진 것이다(예전의 빈 {@code WIKI_MAIL_HOST}와 같은 자리).
 */
@Component
@Slf4j
public class OrgMailClient {

    private static final String SOURCE = "wiki";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    /**
     * 한 요청의 수신자 상한(org 계약) — 넘기면 허브가 400으로 거절한다. 잘라내지 않고 이 크기로
     * 나눠 보낸다: 101번째 사람만 조용히 알림을 못 받는 것이 가장 찾기 어려운 종류의 버그다.
     */
    private static final int MAX_RECIPIENTS = 100;
    /**
     * "메일이 켜져 있는가"의 캐시 수명. 설정 화면 조회와 커밋 뒤 발송이 매번 물으면 왕복이 붙는다.
     * 대가는 지연이다 — 관리자가 방금 켰어도 위키가 알아채는 데 최대 이만큼 걸리고, 상태 조회가
     * 한 번 실패하면(=꺼짐으로 본다) 그동안 즉시 알림 메일이 나가지 않는다. 사본 채널이라 감수한다.
     */
    static final Duration STATUS_TTL = Duration.ofSeconds(60);

    private final HttpClient http;
    private final ObjectMapper json;
    private final String baseUri;
    private final String token;
    private final URI sendUri;
    private final URI statusUri;

    private volatile Boolean cachedEnabled;
    private volatile long cachedAt;

    public OrgMailClient(@Value("${platform.wiki.org.internal-uri:http://localhost:9130}") String internalUri,
                         @Value("${platform.wiki.org.internal-token:}") String internalToken,
                         ObjectMapper json) {
        String trimmed = internalUri == null ? "" : internalUri.trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        this.baseUri = trimmed;
        this.token = internalToken == null ? "" : internalToken.trim();
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.sendUri = this.baseUri.isEmpty() ? null : URI.create(this.baseUri + "/internal/org/mail");
        this.statusUri = this.baseUri.isEmpty() ? null : URI.create(this.baseUri + "/internal/org/mail/status");
    }

    /** 허브가 메일을 보낼 수 있는 상태인가 — 설정 화면이 "스위치를 켜도 아무것도 안 온다"를 먼저 말하는 근거. */
    public boolean enabled() {
        if (!usable()) return false;
        Boolean cached = this.cachedEnabled;
        if (cached != null && System.nanoTime() - cachedAt < STATUS_TTL.toNanos()) return cached;
        boolean fetched = fetchEnabled();
        this.cachedEnabled = fetched;
        this.cachedAt = System.nanoTime();
        return fetched;
    }

    /**
     * 한 통을 허브에 넘긴다. 반환값은 "허브가 받아 큐에 넣었는가"다 — 실제 SMTP 발송은 org의
     * outbox 워커가 하고, 그 결과는 관리 화면의 발송 로그에 남는다.
     *
     * <p>수신자가 {@value #MAX_RECIPIENTS}명을 넘으면 그 크기로 나눠 여러 번 부른다. 전부 받아들여져야
     * {@code true}다 — 한 묶음이라도 거절당하면 "보냈다"고 말하지 않는다.
     */
    public boolean send(List<String> to, String subject, String text) {
        List<String> recipients = clean(to);
        if (recipients.isEmpty()) return false;
        if (!usable()) return false;

        boolean all = true;
        for (int from = 0; from < recipients.size(); from += MAX_RECIPIENTS) {
            List<String> chunk = recipients.subList(from, Math.min(from + MAX_RECIPIENTS, recipients.size()));
            Outcome outcome = post(chunk, subject, text);
            // 꺼져 있다는 답은 묶음이 아니라 허브의 상태다 — 남은 묶음도 같은 답을 받는다
            if (outcome == Outcome.DISABLED) return false;
            if (outcome == Outcome.FAILED) all = false;
        }
        return all;
    }

    private enum Outcome { ACCEPTED, DISABLED, FAILED }

    private Outcome post(List<String> recipients, String subject, String text) {
        ObjectNode body = json.createObjectNode();
        ArrayNode addresses = body.putArray("to");
        recipients.forEach(addresses::add);
        body.put("subject", subject == null ? "" : subject);
        body.put("text", text == null ? "" : text);
        body.put("source", SOURCE);

        try {
            HttpRequest request = HttpRequest.newBuilder(sendUri)
                    .timeout(READ_TIMEOUT)
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .header("X-Internal-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("메일 허브가 발송을 거절했다: status={} to={} subject={} body={}",
                        response.statusCode(), recipients, subject, response.body());
                return Outcome.FAILED;
            }
            if (disabled(response.body())) {
                // 관리 화면에서 꺼 둔 상태다 — 정상이므로 경고하지 않는다(다음 status 조회가 화면에도 반영한다)
                log.debug("메일 허브가 꺼져 있어 보내지 않는다: to={} subject={}", recipients, subject);
                cache(false);
                return Outcome.DISABLED;
            }
            return Outcome.ACCEPTED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("메일 허브 발송이 중단됐다: to={} subject={}", recipients, subject);
            return Outcome.FAILED;
        } catch (Exception e) {
            log.warn("메일 허브에 발송을 넘기지 못했다: to={} subject={}", recipients, subject, e);
            return Outcome.FAILED;
        }
    }

    private boolean fetchEnabled() {
        try {
            HttpRequest request = HttpRequest.newBuilder(statusUri)
                    .timeout(READ_TIMEOUT)
                    .header("X-Internal-Token", token)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("메일 허브 상태를 읽지 못했다(꺼진 것으로 본다): status={} body={}",
                        response.statusCode(), response.body());
                return false;
            }
            JsonNode node = json.readTree(response.body());
            return node.path("enabled").asBoolean(false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("메일 허브 상태 조회에 실패했다(꺼진 것으로 본다): uri={}", statusUri, e);
            return false;
        }
    }

    private boolean disabled(String responseBody) {
        try {
            return json.readTree(responseBody).path("disabled").asBoolean(false);
        } catch (Exception e) {
            // 202를 받았으면 받아들여진 것이다 — 본문을 못 읽었다고 실패로 뒤집지 않는다
            log.debug("메일 허브 응답 본문을 읽지 못했다: {}", responseBody);
            return false;
        }
    }

    private void cache(boolean value) {
        this.cachedEnabled = value;
        this.cachedAt = System.nanoTime();
    }

    /**
     * 주소를 모르면 부르지 않는다 — 빈 주소는 org에서도 버려진다. 같은 주소가 두 번 들어오면
     * 한 번만 남긴다: 한 사람에게 같은 메일을 두 통 보내는 것은 아무에게도 이롭지 않고,
     * 수신자 상한도 그만큼 헛되이 찬다.
     */
    private List<String> clean(List<String> to) {
        if (to == null) return List.of();
        Set<String> unique = new LinkedHashSet<>();
        for (String address : to) {
            if (address == null || address.isBlank()) continue;
            unique.add(address.trim());
        }
        return new ArrayList<>(unique);
    }

    private boolean usable() {
        return !token.isEmpty() && sendUri != null;
    }
}
