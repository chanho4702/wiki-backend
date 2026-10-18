package com.platform.wikibackend.grpc;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 콘텐츠 gRPC 서버를 켜는 스위치(감사 W-09).
 *
 * 이 서버의 유일한 소비자는 search-service(OpenSearch 색인기)다. SEARCH_MODE=lite(기본 설치)에는
 * search-service가 없으므로, 권한 검사 없이 전 문서를 주는 서버를 띄워 둘 이유가 없다.
 * SEARCH_MODE를 모르는 배포(값 없음)는 예전처럼 켠다 — 옛 compose가 opensearch 모드에서 색인을 잃지 않게.
 */
class GrpcServerSwitchTest {

    private static boolean enabled(String... props) {
        MockEnvironment env = new MockEnvironment();
        for (int i = 0; i < props.length; i += 2) env.setProperty(props[i], props[i + 1]);
        return WikiGrpcServerCondition.enabled(env);
    }

    @Test
    void 기본값은_켜짐이다() {
        assertThat(enabled()).isTrue();
    }

    @Test
    void lite_모드면_끈다() {
        assertThat(enabled("platform.search.mode", "lite")).isFalse();
        assertThat(enabled("platform.search.mode", " LITE ")).isFalse();
    }

    @Test
    void opensearch와_external_모드는_켠다() {
        assertThat(enabled("platform.search.mode", "opensearch")).isTrue();
        assertThat(enabled("platform.search.mode", "external")).isTrue();
    }

    @Test
    void 명시적으로_끄면_모드와_무관하게_꺼진다() {
        assertThat(enabled("platform.grpc.enabled", "false", "platform.search.mode", "opensearch")).isFalse();
    }
}
