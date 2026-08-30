package com.platform.wikibackend.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** WIKI_MAIL_HOST가 비면 채널은 꺼져 있다 — 설정 화면이 그 사실을 알린다. */
@SpringBootTest
@ActiveProfiles("test")
class EmailChannelDisabledTest {

    @Autowired EmailNotifier email;

    @Test
    void 호스트가_없으면_구성되지_않은_것이다() {
        assertThat(email.configured()).isFalse();
    }
}
