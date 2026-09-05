package com.platform.wikibackend.importapi;

import com.platform.wikibackend.config.InternalTokenFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 내부 토큰을 설정하지 않은 인스턴스(기본값)에서는 import API가 통째로 닫혀 있다.
 *
 * 이관을 쓰지 않는 배포가 훨씬 많다. 그런 인스턴스가 "아무 토큰이나 맞는" 상태로 뜨는 것이
 * 이 경로에서 가장 위험한 실패이므로, 기본 설정 그대로의 컨텍스트에서 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class WikiImportTokenUnsetTest {

    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void 토큰_미설정이면_어떤_헤더로도_통과하지_못한다() throws Exception {
        mvc.perform(post("/internal/wiki/import/pages")
                        .header(InternalTokenFilter.TOKEN_HEADER, "")
                        .header(InternalTokenFilter.ACTOR_HEADER, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/wiki/import/pages")
                        .header(InternalTokenFilter.TOKEN_HEADER, "아무거나")
                        .header(InternalTokenFilter.ACTOR_HEADER, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/wiki/import/spaces/1"))
                .andExpect(status().isForbidden());
    }
}
