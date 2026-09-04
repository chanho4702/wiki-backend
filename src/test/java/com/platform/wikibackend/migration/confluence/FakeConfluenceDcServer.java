package com.platform.wikibackend.migration.confluence;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 실기 DC를 대신하는 최소 REST 서버(JDK HttpServer).
 *
 * 실제 DC의 JSON을 완벽히 흉내 내지 않는다 — 정규화기가 읽는 필드와 페이지네이션·404만 재현해
 * **우리 파이프라인의 규칙**(순서·멱등·갱신·데드레터)을 검증하는 것이 목적이다. 실기 실측은
 * G5 게이트로 따로 남아 있다.
 */
public class FakeConfluenceDcServer {

    private final HttpServer server;
    private final Map<String, PageRow> rows = new LinkedHashMap<>();

    public FakeConfluenceDcServer() throws IOException {
        seed();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/api/space/", exchange -> respond(exchange, 200, """
                {"name":"Engineering","homepage":{"id":"10001"}}"""));
        server.createContext("/rest/api/content", this::handleContent);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    /** 원본이 수정된 상황 — 재이관이 갱신으로 이어지는지 보려고 쓴다. */
    public void updatePage(String id, String title, String storage, int version) {
        PageRow row = rows.get(id);
        rows.put(id, new PageRow(id, title, row.parentId(), storage, version, row.labels()));
    }

    /** 발견 뒤 원본에서 사라진 상황 — 404가 데드레터로 가는지 보려고 쓴다. */
    public void removePage(String id) {
        rows.remove(id);
    }

    private void seed() {
        rows.put("10001", new PageRow("10001", "서비스 운영 가이드", null, """
                <h1>서비스 운영 가이드</h1>\
                <p><strong>운영 변경</strong> 전 확인하세요.</p>\
                <ac:structured-macro ac:name="warning"><ac:parameter ac:name="title">운영 주의</ac:parameter>\
                <ac:rich-text-body><p>백업이 필요합니다.</p></ac:rich-text-body></ac:structured-macro>\
                <ac:structured-macro ac:name="jira" ac:schema-version="1">\
                <ac:parameter ac:name="key">OPS-42</ac:parameter></ac:structured-macro>""",
                27, List.of("운영", "런북")));
        rows.put("10002", new PageRow("10002", "장애 대응 절차", "10001",
                "<p>먼저 담당자를 호출합니다.</p>", 3, List.of()));
        rows.put("10003", new PageRow("10003", "복구 체크리스트", "10002",
                "<ul><li>백업 확인</li><li>담당자 확인</li></ul>", 1, List.of()));
    }

    private void handleContent(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/rest/api/content/")) {
            String id = URLDecoder.decode(path.substring("/rest/api/content/".length()),
                    StandardCharsets.UTF_8);
            PageRow row = rows.get(id);
            if (row == null) {
                respond(exchange, 404, "{}");
                return;
            }
            respond(exchange, 200, detail(row));
            return;
        }
        Map<String, String> query = queryOf(exchange.getRequestURI().getRawQuery());
        int start = Integer.parseInt(query.getOrDefault("start", "0"));
        int limit = Integer.parseInt(query.getOrDefault("limit", "100"));
        List<PageRow> all = new ArrayList<>(rows.values());
        List<PageRow> window = start >= all.size()
                ? List.of()
                : all.subList(start, Math.min(start + limit, all.size()));
        StringBuilder results = new StringBuilder();
        for (PageRow row : window) {
            if (!results.isEmpty()) {
                results.append(',');
            }
            results.append(summary(row));
        }
        respond(exchange, 200,
                "{\"results\":[" + results + "],\"size\":" + window.size()
                        + ",\"totalSize\":" + all.size() + "}");
    }

    private String summary(PageRow row) {
        return "{\"id\":\"" + row.id() + "\",\"type\":\"page\",\"status\":\"current\",\"title\":\""
                + row.title() + "\",\"version\":{\"number\":" + row.version()
                + "},\"ancestors\":[" + ancestors(row) + "]}";
    }

    private String detail(PageRow row) {
        StringBuilder labels = new StringBuilder();
        for (String label : row.labels()) {
            if (!labels.isEmpty()) {
                labels.append(',');
            }
            labels.append("{\"name\":\"").append(label).append("\"}");
        }
        String attachments = "10001".equals(row.id())
                ? "{\"id\":\"att-1\",\"title\":\"topology.png\","
                        + "\"extensions\":{\"mediaType\":\"image/png\",\"fileSize\":2048}}"
                : "";
        return "{\"id\":\"" + row.id() + "\",\"type\":\"page\",\"status\":\"current\",\"title\":\""
                + row.title() + "\","
                + "\"space\":{\"key\":\"ENG\",\"name\":\"Engineering\"},"
                + "\"version\":{\"number\":" + row.version() + ",\"when\":\"2026-08-17T00:00:00Z\"},"
                + "\"ancestors\":[" + ancestors(row) + "],"
                + "\"history\":{\"createdDate\":\"2026-01-02T03:04:05Z\","
                + "\"createdBy\":{\"username\":\"ops\",\"displayName\":\"김운영\","
                + "\"email\":\"ops@example.com\"}},"
                + "\"metadata\":{\"labels\":{\"results\":[" + labels + "]}},"
                + "\"body\":{\"storage\":{\"value\":\"" + escape(row.storage())
                + "\",\"representation\":\"storage\"}},"
                + "\"children\":{\"attachment\":{\"results\":[" + attachments + "]}},"
                + "\"_links\":{\"next\":\"http://169.254.169.254/should-not-follow\"}}";
    }

    /** 루트에서 부모 순서 — DC가 주는 순서 그대로다. */
    private String ancestors(PageRow row) {
        List<String> chain = new ArrayList<>();
        String parent = row.parentId();
        while (parent != null) {
            chain.add(0, parent);
            PageRow parentRow = rows.get(parent);
            parent = parentRow == null ? null : parentRow.parentId();
        }
        StringBuilder out = new StringBuilder();
        for (String id : chain) {
            if (!out.isEmpty()) {
                out.append(',');
            }
            out.append("{\"id\":\"").append(id).append("\"}");
        }
        return out.toString();
    }

    private static Map<String, String> queryOf(String raw) {
        Map<String, String> query = new LinkedHashMap<>();
        if (raw == null) {
            return query;
        }
        for (String pair : raw.split("&")) {
            int index = pair.indexOf('=');
            if (index > 0) {
                query.put(pair.substring(0, index),
                        URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
            }
        }
        return query;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private record PageRow(String id, String title, String parentId, String storage, int version,
                           List<String> labels) {
    }
}
