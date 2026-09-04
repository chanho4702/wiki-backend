package com.platform.wikibackend.migration.confluence.dc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.wikibackend.migration.worker.MigrationStageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Confluence Server/Data Center REST 클라이언트. 새 의존성 없이 JDK HttpClient만 쓴다.
 *
 * 두 가지를 구조로 막는다.
 * - **SSRF**: 요청 URI는 검증된 baseUrl + 이 클래스가 가진 고정 경로로만 만든다. 응답의 `_links.next`를
 *   따라가지 않고 start 오프셋을 우리가 센다. 리다이렉트는 따라가지 않는다(따라가면 baseUrl 검증이 무의미해진다).
 * - **토큰 유출**: 자격증명은 어떤 로그·예외 메시지에도 넣지 않는다. 실패는 코드 문자열로만 말한다.
 */
@Component
@Slf4j
public class ConfluenceDcClient {

    private final ObjectMapper objectMapper;
    private final ConfluenceDcProperties properties;
    private final HttpClient http;

    public ConfluenceDcClient(ObjectMapper objectMapper, ConfluenceDcProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 연결 확인 — 스페이스 이름·홈페이지 id와, 사이트가 알려주면 페이지 총계까지. */
    public ConfluenceSpaceProbe probe(ConfluenceDcCredentials credentials) {
        JsonNode space = get(credentials, "/rest/api/space/" + encode(credentials.spaceKey())
                + "?expand=homepage");
        String name = space.path("name").asText(null);
        String homepageId = space.path("homepage").path("id").asText(null);

        Integer pageCount = null;
        try {
            JsonNode probe = get(credentials, "/rest/api/content?spaceKey=" + encode(credentials.spaceKey())
                    + "&type=page&status=current&limit=1");
            // DC 버전에 따라 totalSize가 없다. 없으면 null로 두고 화면이 "발견 후 확인"을 안내한다.
            JsonNode total = probe.hasNonNull("totalSize") ? probe.get("totalSize") : probe.get("size");
            if (total != null && total.isIntegralNumber()) {
                pageCount = total.intValue();
            }
        } catch (MigrationStageException exception) {
            // 총계는 부가 정보다. 스페이스가 보이는데 개수만 못 세는 사이트 때문에 연결 확인을 실패시키지 않는다.
            log.warn("원본 페이지 수 조회 실패 — 총계 없이 진행한다: code={}", exception.getCode());
        }
        return new ConfluenceSpaceProbe(name, homepageId, pageCount);
    }

    /**
     * 페이지 한 묶음. `_links.next`가 아니라 우리가 센 start를 넘긴다 — 다음 주소를 원본이
     * 정하게 두면 검증한 baseUrl 밖으로 끌려갈 수 있다.
     */
    public ConfluenceContentPage listPages(ConfluenceDcCredentials credentials, int start) {
        int limit = properties.pageSize();
        JsonNode response = get(credentials, "/rest/api/content"
                + "?spaceKey=" + encode(credentials.spaceKey())
                + "&type=page&status=current&expand=version,ancestors"
                + "&start=" + start + "&limit=" + limit);
        JsonNode results = response.path("results");
        if (!results.isArray()) {
            throw MigrationStageException.permanent(ConfluenceDcCodes.INVALID_RESPONSE);
        }
        List<ConfluenceContentSummary> page = new ArrayList<>(results.size());
        for (JsonNode node : results) {
            String id = node.path("id").asText(null);
            if (id == null || id.isBlank()) {
                throw MigrationStageException.permanent(ConfluenceDcCodes.INVALID_RESPONSE);
            }
            List<String> ancestors = new ArrayList<>();
            for (JsonNode ancestor : node.path("ancestors")) {
                String ancestorId = ancestor.path("id").asText(null);
                if (ancestorId != null && !ancestorId.isBlank()) {
                    ancestors.add(ancestorId);
                }
            }
            page.add(new ConfluenceContentSummary(id, node.path("title").asText(""),
                    node.path("version").path("number").asInt(1), List.copyOf(ancestors)));
        }
        // 마지막 묶음 판정: 받은 수가 limit보다 적으면 끝이다. size 필드를 믿지 않는 이유는
        // 버전에 따라 없거나 전체 총계를 담는 사이트가 있기 때문이다.
        return new ConfluenceContentPage(List.copyOf(page), page.size() >= limit);
    }

    /** 본문·이력·라벨·첨부 목록까지 한 번에 — 페이지당 왕복을 늘리지 않는다. */
    public JsonNode content(ConfluenceDcCredentials credentials, String contentId) {
        return get(credentials, "/rest/api/content/" + encode(contentId)
                + "?expand=body.storage,version,ancestors,history,metadata.labels,children.attachment");
    }

    private JsonNode get(ConfluenceDcCredentials credentials, String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(credentials.baseUrl() + path))
                .timeout(properties.readTimeout())
                .header("Authorization", "Bearer " + credentials.token())
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            // 예외 메시지에 URI가 붙을 수 있어 cause를 붙이지 않는다 — 토큰은 헤더라 안 새지만
            // 원본 주소도 굳이 손실 보고서에 남길 값이 아니다.
            throw MigrationStageException.retryable(ConfluenceDcCodes.UNAVAILABLE);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw MigrationStageException.retryable(ConfluenceDcCodes.UNAVAILABLE);
        }
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            throw MigrationStageException.permanent(ConfluenceDcCodes.REDIRECT_REFUSED);
        }
        if (status == 401 || status == 403) {
            throw MigrationStageException.permanent(ConfluenceDcCodes.AUTH);
        }
        if (status == 404) {
            throw MigrationStageException.permanent(ConfluenceDcCodes.NOT_FOUND);
        }
        if (status == 429 || status >= 500) {
            throw MigrationStageException.retryable(ConfluenceDcCodes.UNAVAILABLE);
        }
        if (status >= 400) {
            throw MigrationStageException.permanent(ConfluenceDcCodes.INVALID_RESPONSE);
        }
        try {
            JsonNode body = objectMapper.readTree(response.body());
            if (body == null || !body.isObject()) {
                throw MigrationStageException.permanent(ConfluenceDcCodes.INVALID_RESPONSE);
            }
            return body;
        } catch (IOException exception) {
            throw MigrationStageException.permanent(ConfluenceDcCodes.INVALID_RESPONSE);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
