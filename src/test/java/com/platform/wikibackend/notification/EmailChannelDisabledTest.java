package com.platform.wikibackend.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `ORG_INTERNAL_TOKEN`이 비면 채널은 꺼져 있다 — 설정 화면이 그 사실을 알린다.
 *
 * <p>기본 테스트 컨텍스트에는 허브 토큰이 없다. 그러면 {@link OrgMailClient}는 상태를 물으러 나가지도
 * 않는다(테스트 JVM에는 org-service가 없다).
 */
@SpringBootTest
@ActiveProfiles("test")
class EmailChannelDisabledTest {

    @Autowired EmailNotifier email;

    @Test
    void 허브_토큰이_없으면_채널은_꺼져_있다() {
        assertThat(email.enabled()).isFalse();
    }
}
