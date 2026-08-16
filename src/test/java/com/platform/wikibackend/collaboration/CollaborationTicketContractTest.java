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
}
