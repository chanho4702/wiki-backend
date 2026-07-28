package com.platform.wikibackend.schema;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.domain.PageType;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway 마이그레이션 ↔ JPA 엔티티 정합을 실제 Postgres로 검증한다.
 *
 * 나머지 테스트는 H2 + `ddl-auto: create-drop`이라 **마이그레이션을 아예 타지 않는다** —
 * 엔티티에 필드를 추가하고 `V*.sql`을 빠뜨려도 전부 통과하고, 운영에서 `ddl-auto: validate`가
 * 부팅을 거부한다. 그 간극을 이 테스트가 메운다.
 *
 * 여기서 하는 일:
 * 1. 빈 Postgres에 Flyway로 V1→V2를 적용하고
 * 2. `ddl-auto: validate`로 컨텍스트를 띄운다(불일치면 컨텍스트 로딩 실패 = 테스트 실패)
 * 3. 실제 INSERT/SELECT로 H2에는 없는 제약(FK·CHECK)까지 살아 있는지 본다.
 *
 * Docker가 필요하다. 없으면 이 테스트만 실패하므로, 로컬에서 Docker 없이 돌릴 땐
 * `--tests '*' -x` 대신 `./gradlew test --tests '!*FlywaySchemaValidationTest'`로 제외한다.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none",
})
@ActiveProfiles("test")
@Testcontainers
class FlywaySchemaValidationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;

    /**
     * 컨텍스트가 떴다는 것 자체가 "마이그레이션 결과 스키마 == 엔티티 매핑"의 증거다
     * (validate가 컬럼 누락·타입 불일치에서 부팅을 거부한다).
     */
    @Test
    void 마이그레이션_스키마로_엔티티_검증이_통과한다() {
        assertThat(postgres.isRunning()).isTrue();
    }

    @Test
    void V2가_추가한_type_status가_실제로_저장되고_읽힌다() {
        Space s = spaces.save(Space.of("dev", "개발", null, 1L));
        Page folder = pages.save(Page.of(s.getId(), null, "폴더", "", 1L, PageType.FOLDER, PageStatus.PUBLISHED));
        Page draft = pages.save(Page.of(s.getId(), null, "초안", "", 1L, PageType.PAGE, PageStatus.DRAFT));

        assertThat(pages.findById(folder.getId()).orElseThrow().getType()).isEqualTo(PageType.FOLDER);
        assertThat(pages.findById(draft.getId()).orElseThrow().getStatus()).isEqualTo(PageStatus.DRAFT);
    }

    /**
     * H2 테스트 스키마엔 FK가 아예 없다(Wave B에서 세 번 물린 지점) — 운영 Postgres에는
     * `page.parent_id → page.id ON DELETE CASCADE`가 실제로 걸려 있는지 확인한다.
     */
    @Test
    void 부모_페이지를_지우면_DB_cascade로_자식도_사라진다() {
        Space s = spaces.save(Space.of("ops", "운영", null, 1L));
        Page parent = pages.save(Page.of(s.getId(), null, "부모", "", 1L));
        Page child = pages.save(Page.of(s.getId(), parent.getId(), "자식", "", 1L));

        pages.deleteById(parent.getId());
        pages.flush();

        assertThat(pages.findById(child.getId())).isEmpty();
    }
}
