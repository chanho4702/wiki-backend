package com.platform.wikibackend.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Actuator 헬스·빌드정보(설계 §3).
 *
 * 게이트웨이의 플랫폼 상태 집계가 컨테이너 네트워크에서 **토큰 없이** 이 두 경로를 프로브한다.
 * 인증을 요구하게 되는 순간 상태판 전체가 UNKNOWN으로 죽으므로 permitAll을 회귀로 못 박는다.
 * 동시에 다른 actuator 표면이 따라 열리지 않는지도 본다 — 노출 목록에 health·info만 있다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ActuatorEndpointTest {

    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void 헬스는_토큰_없이_200이다() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /** show-details: always — 집계 쪽이 components.db를 읽어 Postgres 상태를 판정한다. */
    @Test
    void 헬스가_구성요소_상세를_싣는다() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    void 빌드정보는_토큰_없이_200이다() throws Exception {
        mvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());
    }

    /**
     * 나머지 actuator 표면은 닿지 않는다. 방어가 두 겹이라 401이 나온다 —
     * 노출 목록(health·info)에 없어 매핑 자체가 없고, permitAll 목록에도 없어 그 앞의
     * {@code anyRequest().authenticated()}가 먼저 받는다. 200이 나오면 둘 다 새어 있다는 뜻이다.
     */
    @Test
    void 나머지_actuator_표면은_노출되지_않는다() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/beans")).andExpect(status().isUnauthorized());
    }
}
