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
 * 실제 DC의 JSON을 완벽히 흉내 내지 않는다 — 정규화기가 읽는 필드와 페이지네이션·404·첨부
 * 내려받기·자식 순서·제한만 재현해 **우리 파이프라인의 규칙**(순서·멱등·갱신·데드레터·fail-closed)을
 * 검증하는 것이 목적이다. 실기 실측은 G5 게이트로 따로 남아 있다.
 */
public class FakeConfluenceDcServer {

    /** PNG 매직 — AttachmentMediaTypes.detect가 image/png로 읽어야 본문이 인라인 주소로 바뀐다. */
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07};

    /** PDF 매직 — 이미지가 아니라 내려받기 주소로 가는 경로를 태운다. */
    private static final byte[] PDF = "%PDF-1.4\n첨부 본문".getBytes(StandardCharsets.UTF_8);

    private final HttpServer server;
    private final Map<String, PageRow> rows = new LinkedHashMap<>();

    public FakeConfluenceDcServer() throws IOException {
        seed();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/api/space/", this::handleSpace);
        server.createContext("/rest/api/content", this::handleContent);
        server.createContext("/download/attachments/", this::handleDownload);
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
        rows.put(id, new PageRow(id, title, row.parentId(), storage, version, row.labels(),
                row.attachments(), row.restrictions()));
    }

    /** 발견 뒤 원본에서 사라진 상황 — 404가 데드레터로 가는지 보려고 쓴다. */
    public void removePage(String id) {
        rows.remove(id);
    }

    /** 페이지 하나를 통째로 갈아끼운다 — 첨부·제한·본문을 시나리오마다 다르게 두려고 쓴다. */
    public void putPage(String id, String title, String parentId, String storage, int version,
                        List<String> labels, List<FakeAttachment> attachments,
                        FakeRestrictions restrictions) {
        rows.put(id, new PageRow(id, title, parentId, storage, version, labels, attachments,
                restrictions));
    }

    /** 형제 순서를 바꾼다 — `child/page`가 돌려주는 순서가 곧 원본 정렬이다. */
    public void reorder(List<String> idsInOrder) {
        Map<String, PageRow> reordered = new LinkedHashMap<>();
        for (String id : idsInOrder) {
            PageRow row = rows.get(id);
            if (row != null) {
                reordered.put(id, row);
            }
        }
        for (Map.Entry<String, PageRow> entry : rows.entrySet()) {
            reordered.putIfAbsent(entry.getKey(), entry.getValue());
        }
        rows.clear();
        rows.putAll(reordered);
    }

    public static FakeAttachment png(String filename) {
        return new FakeAttachment(filename, "image/png", PNG, 1, PNG.length);
    }

    public static FakeAttachment pdf(String filename) {
        return new FakeAttachment(filename, "application/pdf", PDF, 1, PDF.length);
    }

    /** 실제 바이트는 작지만 목록에는 거대하다고 알리는 첨부 — 크기 상한 경로를 태운다. */
    public static FakeAttachment oversized(String filename, long declaredSize) {
        return new FakeAttachment(filename, "application/octet-stream",
                new byte[]{0, 1, 2, 3}, 1, declaredSize);
    }

    private void seed() {
        rows.put("10001", new PageRow("10001", "서비스 운영 가이드", null, """
                <h1>서비스 운영 가이드</h1>\
                <p><strong>운영 변경</strong> 전 확인하세요.</p>\
                <ac:structured-macro ac:name="warning"><ac:parameter ac:name="title">운영 주의</ac:parameter>\
                <ac:rich-text-body><p>백업이 필요합니다.</p></ac:rich-text-body></ac:structured-macro>\
                <ac:structured-macro ac:name="jira" ac:schema-version="1">\
                <ac:parameter ac:name="key">OPS-42</ac:parameter></ac:structured-macro>""",
                27, List.of("운영", "런북"), List.of(png("topology.png")), FakeRestrictions.none()));
        rows.put("10002", new PageRow("10002", "장애 대응 절차", "10001",
                "<p>먼저 담당자를 호출합니다.</p>", 3, List.of(), List.of(), FakeRestrictions.none()));
        rows.put("10003", new PageRow("10003", "복구 체크리스트", "10002",
                "<ul><li>백업 확인</li><li>담당자 확인</li></ul>", 1, List.of(), List.of(),
                FakeRestrictions.none()));
    }

    private void handleSpace(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/content/page")) {
            // 스페이스 최상단 목록 — 루트의 형제 순서다.
            respond(exchange, 200, summaries(childrenOf(null)));
            return;
        }
        respond(exchange, 200, """
                {"name":"Engineering","homepage":{"id":"10001"}}""");
    }

    private void handleContent(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/child/page")) {
            String id = URLDecoder.decode(path.substring("/rest/api/content/".length(),
                    path.length() - "/child/page".length()), StandardCharsets.UTF_8);
            respond(exchange, 200, summaries(childrenOf(id)));
            return;
        }
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

    /** `/download/attachments/{pageId}/{filename}?version=N` — 고정 패턴만 받는다. */
    private void handleDownload(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String[] segments = exchange.getRequestURI().getPath().split("/");
        if (segments.length < 5) {
            respond(exchange, 404, "{}");
            return;
        }
        String pageId = URLDecoder.decode(segments[3], StandardCharsets.UTF_8);
        String filename = URLDecoder.decode(segments[4], StandardCharsets.UTF_8);
        PageRow row = rows.get(pageId);
        if (row == null) {
            respond(exchange, 404, "{}");
            return;
        }
        for (FakeAttachment file : row.attachments()) {
            if (file.filename().equals(filename)) {
                exchange.getResponseHeaders().add("Content-Type", file.mediaType());
                exchange.sendResponseHeaders(200, file.bytes().length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(file.bytes());
                }
                return;
            }
        }
        respond(exchange, 404, "{}");
    }

    private List<PageRow> childrenOf(String parentId) {
        List<PageRow> children = new ArrayList<>();
        for (PageRow row : rows.values()) {
            if (parentId == null ? row.parentId() == null : parentId.equals(row.parentId())) {
                children.add(row);
            }
        }
        return children;
    }

    private String summaries(List<PageRow> children) {
        StringBuilder results = new StringBuilder();
        for (PageRow row : children) {
            if (!results.isEmpty()) {
                results.append(',');
            }
            results.append(summary(row));
        }
        return "{\"results\":[" + results + "],\"size\":" + children.size() + "}";
    }

    private String summary(PageRow row) {
        return "{\"id\":\"" + row.id() + "\",\"type\":\"page\",\"status\":\"current\",\"title\":\""
                + escape(row.title()) + "\",\"version\":{\"number\":" + row.version()
                + "},\"ancestors\":[" + ancestors(row) + "]}";
    }

    private String detail(PageRow row) {
        StringBuilder labels = new StringBuilder();
        for (String label : row.labels()) {
            if (!labels.isEmpty()) {
                labels.append(',');
            }
            labels.append("{\"name\":\"").append(escape(label)).append("\"}");
        }
        StringBuilder attachments = new StringBuilder();
        for (FakeAttachment file : row.attachments()) {
            if (!attachments.isEmpty()) {
                attachments.append(',');
            }
            attachments.append("{\"id\":\"att-").append(escape(file.filename()))
                    .append("\",\"title\":\"").append(escape(file.filename()))
                    .append("\",\"version\":{\"number\":").append(file.version())
                    .append("},\"extensions\":{\"mediaType\":\"").append(file.mediaType())
                    .append("\",\"fileSize\":").append(file.declaredSize()).append("}}");
        }
        return "{\"id\":\"" + row.id() + "\",\"type\":\"page\",\"status\":\"current\",\"title\":\""
                + escape(row.title()) + "\","
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
                + "\"restrictions\":" + row.restrictions().toJson() + ","
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

    /** 목록이 알리는 크기(declaredSize)와 실제 바이트를 따로 둔다 — 상한 판정은 목록을 먼저 본다. */
    public record FakeAttachment(String filename, String mediaType, byte[] bytes, int version,
                                 long declaredSize) {
    }

    /** DC의 네 겹 제한 응답을 그대로 흉내 낸다. */
    public record FakeRestrictions(List<String> readUsers, List<String> readGroups,
                                   List<String> updateUsers, List<String> updateGroups) {

        public static FakeRestrictions none() {
            return new FakeRestrictions(List.of(), List.of(), List.of(), List.of());
        }

        public static FakeRestrictions readBy(List<String> users, List<String> groups) {
            return new FakeRestrictions(users, groups, List.of(), List.of());
        }

        String toJson() {
            return "{\"read\":" + group(readUsers, readGroups)
                    + ",\"update\":" + group(updateUsers, updateGroups) + "}";
        }

        private static String group(List<String> users, List<String> groups) {
            StringBuilder userJson = new StringBuilder();
            for (String user : users) {
                if (!userJson.isEmpty()) {
                    userJson.append(',');
                }
                userJson.append("{\"username\":\"").append(escape(user))
                        .append("\",\"displayName\":\"").append(escape(user))
                        .append("\",\"email\":\"").append(escape(user)).append("@example.com\"}");
            }
            StringBuilder groupJson = new StringBuilder();
            for (String group : groups) {
                if (!groupJson.isEmpty()) {
                    groupJson.append(',');
                }
                groupJson.append("{\"name\":\"").append(escape(group)).append("\"}");
            }
            return "{\"restrictions\":{\"user\":{\"results\":[" + userJson
                    + "]},\"group\":{\"results\":[" + groupJson + "]}}}";
        }
    }

    private record PageRow(String id, String title, String parentId, String storage, int version,
                           List<String> labels, List<FakeAttachment> attachments,
                           FakeRestrictions restrictions) {
    }
}
