package com.platform.wikibackend.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CollaborationTicketContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void Redis_payload_v1_schema와_key_namespace를_교차_런타임_계약으로_고정한다() throws Exception {
        JsonNode schema;
        try (InputStream input = getClass().getResourceAsStream(
                "/schema/collaboration-ticket-v1.schema.json")) {
            assertThat(input).isNotNull();
            schema = JSON.readTree(input);
        }

        Set<String> required = new HashSet<>();
        schema.path("required").forEach(node -> required.add(node.asText()));
        assertThat(schema.at("/properties/schemaVersion/const").asInt()).isEqualTo(1);
        assertThat(schema.at("/properties/permission/const").asText()).isEqualTo("EDIT");
        assertThat(schema.at("/properties/room/pattern").asText()).isEqualTo("^page:[1-9][0-9]*$");
        assertThat(required).containsExactlyInAnyOrder(
                "schemaVersion", "pageId", "userId", "displayName", "room", "permission",
                "issuedAt", "expiresAt");
        assertThat(CollaborationTicketService.KEY_PREFIX)
                .isEqualTo("wiki:collaboration:ticket:v1:");
    }

    @Test
    void Redis_payload_v2는_pageVersion과_draftEpoch를_더한_10필드이고_record와_어긋나지_않는다() throws Exception {
        JsonNode schema;
        try (InputStream input = getClass().getResourceAsStream(
                "/schema/collaboration-ticket-v2.schema.json")) {
            assertThat(input).isNotNull();
            schema = JSON.readTree(input);
        }

        Set<String> required = new HashSet<>();
        schema.path("required").forEach(node -> required.add(node.asText()));
        assertThat(schema.at("/properties/schemaVersion/const").asInt())
                .isEqualTo(CollaborationTicketPayload.SCHEMA_VERSION)
                .isEqualTo(2);
        assertThat(schema.at("/properties/pageVersion/type").asText()).isEqualTo("integer");
        assertThat(schema.at("/properties/pageVersion/minimum").asInt()).isEqualTo(1);
        assertThat(schema.at("/properties/draftEpoch/type").asText()).isEqualTo("integer");
        assertThat(schema.at("/properties/draftEpoch/minimum").asInt()).isZero();
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(required).containsExactlyInAnyOrder(
                "schemaVersion", "pageId", "userId", "displayName", "room", "permission",
                "pageVersion", "draftEpoch", "issuedAt", "expiresAt");
        // 스키마와 record 구성요소가 같아야 한다 — 한쪽만 고치면 collaboration-service가 거부한다
        Set<String> components = new HashSet<>();
        for (var component : CollaborationTicketPayload.class.getRecordComponents()) {
            components.add(component.getName());
        }
        assertThat(components).isEqualTo(required);
        // key namespace는 v1 그대로다 — collaboration-service가 두 스키마를 같은 key에서 읽는다
        assertThat(CollaborationTicketService.KEY_PREFIX)
                .isEqualTo("wiki:collaboration:ticket:v1:");
    }
}
