package com.platform.wikibackend.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Document IR v1의 provider 중립 불변식을 golden fixture로 고정한다.
 *
 * JSON Schema는 외부 importer/worker가 공유하는 문법 계약이고, 이 테스트는 JSON Schema만으로
 * 표현하기 어려운 block ID 유일성, media 참조 무결성, 만료 URL 금지 같은 의미 계약을 검증한다.
 * 아직 런타임 저장 포맷을 바꾸지 않으며 fixture도 운영 코드에서 읽지 않는다.
 */
class DocumentIrContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCHEMA = "/schema/document-ir-v1.schema.json";
    private static final String NOTION = "/fixtures/document-ir/notion-page-v1.json";
    private static final String CONFLUENCE = "/fixtures/document-ir/confluence-page-v1.json";

    @Test
    void v1_스키마가_버전과_provider_중립_block_목록을_고정한다() throws IOException {
        JsonNode schema = read(SCHEMA);

        assertThat(schema.path("$schema").asText()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(schema.at("/properties/schemaVersion/const").asInt()).isEqualTo(1);
        assertThat(allowedTypes(schema)).contains(
                "doc", "paragraph", "heading", "text", "table", "panel", "columns",
                "image", "attachment", "pageLink", "mention", "opaque");
    }

    @Test
    void Notion_fixture가_v1_의미_계약을_충족한다() throws IOException {
        validate(read(NOTION), read(SCHEMA));
    }

    @Test
    void Confluence_DC_fixture가_v1_의미_계약을_충족한다() throws IOException {
        validate(read(CONFLUENCE), read(SCHEMA));
    }

    @Test
    void 중복_block_id는_거부한다() throws IOException {
        JsonNode document = read(NOTION);
        String duplicate = document.at("/root/content/0/id").asText();
        ((ObjectNode) document.at("/root/content/1")).put("id", duplicate);

        assertThatThrownBy(() -> validate(document, read(SCHEMA)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("block ID 중복");
    }

    @Test
    void image에_만료_URL을_직접_저장하면_거부한다() throws IOException {
        JsonNode document = read(NOTION);
        ObjectNode attrs = (ObjectNode) document.at("/root/content/3/attrs");
        attrs.remove("mediaId");
        attrs.put("url", "https://secure.notion-static.com/temporary-signed-url");

        assertThatThrownBy(() -> validate(document, read(SCHEMA)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("mediaId");
    }

    private static void validate(JsonNode document, JsonNode schema) {
        assertThat(document.path("schemaVersion").asInt()).as("schemaVersion").isEqualTo(1);
        assertThat(document.path("documentId").asText()).as("documentId").isNotBlank();
        assertThat(document.path("title").isTextual()).as("title").isTrue();
        assertThat(document.at("/source/provider").asText())
                .as("source provider")
                .isIn("native", "notion", "confluence-dc");
        assertThat(document.at("/source/payloadRef").asText()).as("원본 payloadRef").isNotBlank();
        assertThat(document.at("/source/checksum").asText()).as("원본 checksum").matches("[a-f0-9]{64}");

        Set<String> mediaIds = new HashSet<>();
        document.path("assets").forEach(asset -> {
            assertThat(asset.path("mediaId").asText()).as("asset mediaId").isNotBlank();
            assertThat(asset.path("checksum").asText()).as("asset checksum").matches("[a-f0-9]{64}");
            assertThat(mediaIds.add(asset.path("mediaId").asText())).as("mediaId 중복").isTrue();
        });

        JsonNode root = document.path("root");
        assertThat(root.path("type").asText()).as("root type").isEqualTo("doc");
        Set<String> blockIds = new HashSet<>();
        walk(root, allowedTypes(schema), idPattern(schema), blockIds, mediaIds);
    }

    private static void walk(JsonNode block, Set<String> allowedTypes, Pattern idPattern,
                             Set<String> blockIds, Set<String> mediaIds) {
        String id = block.path("id").asText();
        String type = block.path("type").asText();

        assertThat(id).as("block ID").isNotBlank();
        assertThat(idPattern.matcher(id).matches()).as("block ID 형식: %s", id).isTrue();
        assertThat(blockIds.add(id)).as("block ID 중복: %s", id).isTrue();
        assertThat(type).as("block type: %s", id).isIn(allowedTypes);

        if ("text".equals(type)) {
            assertThat(block.path("text").isTextual()).as("text node 본문: %s", id).isTrue();
        }

        if ("image".equals(type) || "attachment".equals(type)) {
            JsonNode attrs = block.path("attrs");
            String mediaId = attrs.path("mediaId").asText();
            assertThat(mediaId).as("%s mediaId: %s", type, id).isNotBlank();
            assertThat(mediaIds).as("선언되지 않은 mediaId: %s", mediaId).contains(mediaId);
            assertThat(attrs.has("src")).as("media src 직접 저장 금지: %s", id).isFalse();
            assertThat(attrs.has("url")).as("media URL 직접 저장 금지: %s", id).isFalse();
        }

        if ("opaque".equals(type)) {
            JsonNode sourceRef = block.path("sourceRef");
            assertThat(sourceRef.path("provider").asText()).as("opaque provider: %s", id)
                    .isIn("notion", "confluence-dc");
            assertThat(sourceRef.path("objectId").asText()).as("opaque objectId: %s", id).isNotBlank();
            assertThat(sourceRef.path("sourceType").asText()).as("opaque sourceType: %s", id).isNotBlank();
            assertThat(sourceRef.path("path").asText()).as("opaque path: %s", id).isNotBlank();
            assertThat(sourceRef.path("checksum").asText()).as("opaque checksum: %s", id)
                    .matches("[a-f0-9]{64}");
        }

        if ("pageLink".equals(type)) {
            JsonNode target = block.at("/attrs/target");
            boolean hasTarget = target.hasNonNull("internalPageId")
                    || target.hasNonNull("externalObjectId")
                    || target.hasNonNull("href");
            assertThat(hasTarget).as("pageLink target: %s", id).isTrue();
        }

        JsonNode content = block.path("content");
        if (!content.isMissingNode()) {
            assertThat(content.isArray()).as("content 배열: %s", id).isTrue();
            content.forEach(child -> walk(child, allowedTypes, idPattern, blockIds, mediaIds));
        }
    }

    private static Set<String> allowedTypes(JsonNode schema) {
        Set<String> result = new HashSet<>();
        schema.at("/$defs/block/properties/type/enum").forEach(node -> result.add(node.asText()));
        assertThat(result).as("schema block type enum").isNotEmpty();
        return result;
    }

    private static Pattern idPattern(JsonNode schema) {
        String pattern = schema.at("/$defs/block/properties/id/pattern").asText();
        assertThat(pattern).as("schema block ID pattern").isNotBlank();
        return Pattern.compile(pattern);
    }

    private static JsonNode read(String resource) throws IOException {
        try (InputStream input = DocumentIrContractTest.class.getResourceAsStream(resource)) {
            assertThat(input).as("resource: %s", resource).isNotNull();
            return JSON.readTree(input);
        }
    }
}
