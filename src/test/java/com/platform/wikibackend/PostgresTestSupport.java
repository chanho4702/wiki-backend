package com.platform.wikibackend;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 실제 PostgreSQL 16 + Flyway 마이그레이션 + {@code ddl-auto=validate}로 도는 테스트의 공용 바탕.
 *
 * 나머지 스위트는 H2 {@code create-drop}이라 마이그레이션을 타지 않는다 — CHECK 제약·FK cascade·
 * 네이티브 쿼리의 타입 추론이 H2에서는 통과하고 운영에서 깨진다(BLOG CHECK 결함이 그렇게 CI를
 * 통과했다). 쓰기 경로가 실제 스키마에서 도는지는 이 바탕을 상속한 클래스가 본다.
 *
 * 컨테이너는 JVM당 하나(싱글턴)다 — 상속한 클래스들이 같은 Spring 컨텍스트와 DB를 나눠 쓴다.
 * 그래서 각 테스트는 자기 스페이스를 새로 만들어 쓰고, 다른 테스트의 행을 가정하지 않는다.
 * Docker가 필요하다(CI ubuntu 러너에는 있다).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none",
})
@ActiveProfiles("test")
public abstract class PostgresTestSupport {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start(); // JVM 종료 시 Testcontainers(Ryuk)가 치운다
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
}
