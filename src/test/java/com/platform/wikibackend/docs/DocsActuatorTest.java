package com.platform.wikibackend.docs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공개 문서 인스턴스(docs)에서도 헬스·빌드정보는 열린다(설계 §3).
 *
 * 이 프로필의 기본값은 `anyRequest().denyAll()`이라, 두 경로를 명시하지 않으면 상태판에서
 * docs-backend만 영영 DOWN으로 보인다. 그 회귀를 여기서 잡는다 — 동시에 denyAll 자체가
 * 느슨해지지 않았는지 관리자 현황 경로로 확인한다.
 *
 * 애너테이션을 {@link DocsSecurityTest}와 똑같이 맞춘 이유: 스프링 테스트 컨텍스트 캐시 키가
 * 프로필과 프로퍼티까지 포함해서다. 하나라도 다르면 docs 컨텍스트를 한 번 더 띄운다.
 */
@SpringBootTest
@ActiveProfiles({"test", "docs"})
@TestPropertySource(properties = "platform.docs.import-token=test-token")
class DocsActuatorTest {

    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void 헬스는_로그인_없이_200이다() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void 빌드정보는_로그인_없이_200이다() throws Exception {
        mvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());
    }

    /** 관리자 현황은 공개 인스턴스에 있을 이유가 없다 — denyAll이 그대로 받아야 한다. */
    @Test
    void 관리자_현황은_열리지_않는다() throws Exception {
        mvc.perform(get("/api/wiki/admin/stats"))
                .andExpect(status().isForbidden());
    }
}
