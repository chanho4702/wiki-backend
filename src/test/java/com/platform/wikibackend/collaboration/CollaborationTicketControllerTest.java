package com.platform.wikibackend.collaboration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class CollaborationTicketControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean CollaborationTicketService tickets;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void JWT_주체로_페이지_편집_ticket을_발급한다() throws Exception {
        Instant expiresAt = Instant.parse("2026-08-16T12:01:00Z");
        when(tickets.issue(42L, "Alice", 7L)).thenReturn(
                new CollaborationTicketResponse("opaque-ticket", "page:7", "/api/wiki/collaboration", expiresAt));

        mvc.perform(post("/api/wiki/pages/7/collaboration-ticket")
                        .with(asUser(42L, "Alice"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.ticket").value("opaque-ticket"))
                .andExpect(jsonPath("$.room").value("page:7"))
                .andExpect(jsonPath("$.websocketPath").value("/api/wiki/collaboration"))
                .andExpect(jsonPath("$.expiresAt").value("2026-08-16T12:01:00Z"));

        verify(tickets).issue(42L, "Alice", 7L);
    }

    @Test
    void 인증_없이는_ticket을_요청할_수_없다() throws Exception {
        mvc.perform(post("/api/wiki/pages/7/collaboration-ticket"))
                .andExpect(status().isUnauthorized());
    }
}
