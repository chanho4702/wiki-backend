# CLAUDE.md — wiki-backend

위키 정본 서비스(스페이스·페이지·리비전·첨부·댓글·알림·라이트 검색). 플랫폼 공통 규약과 확정 설계 결정은
루트 `MSA_TEMPLATE/CLAUDE.md`와 `platform-backend`·`platform-integration` 스킬이 정본이다 — 여기는
이 리포에만 해당하는 규약이다. 작업 기록은 이 파일에 쓰지 않는다(Obsidian).

## 빌드·테스트

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-24"
export GITHUB_TOKEN=$(gh auth token)      # common-proto·common-starter(GitHub Packages)
./gradlew test                            # 전체(H2 + PostgreSQL Testcontainers) — Docker 필요
./gradlew test --tests '*PageControllerTest'
./gradlew bootJar                         # build/libs/app.jar (Dockerfile은 런타임 전용)
```

- 머신에 여러 빌드가 동시에 돌면 Testcontainers가 간헐 실패할 수 있다 — 그 클래스만 단독 재실행해 판정한다.
- CI(`.github/workflows/ci.yml`, ubuntu-latest)는 `./gradlew build`로 PG 테스트까지 돈다. main 푸시는 배포 트리거다.

## 테스트 두 갈래

- **H2 스위트(대부분)**: `application-test.yml` — `ddl-auto: create-drop`, Flyway 꺼짐. 마이그레이션·CHECK·FK·
  네이티브 질의 타입은 **검증하지 못한다**.
- **PostgreSQL 스위트**: `PostgresTestSupport`를 상속(PG 16 싱글턴 컨테이너 + Flyway + `ddl-auto=validate`).
  `schema/PostgresWritePathTest`(쓰기 경로 스모크)·`PostgresPurgeTest`·`FlywaySchemaValidationTest`.
  스키마 제약·네이티브 SQL·"지우고 다시 넣기"·bulk 삭제를 건드리면 여기에 경로를 추가한다.
  컨테이너·컨텍스트를 공유하므로 테스트마다 새 스페이스를 만들고 다른 테스트 행을 가정하지 않는다.
- org-service는 페이크(`FakePermissionClient`·`FakeTeamDirectory`·`FakePrincipalDirectory`·`FakeMemberDirectory`)로 대체한다.
  gRPC 클라이언트 자체는 in-process 서버로 코드별 분기를 검증한다.
- 질의 수·엔티티 로드 회귀는 `hibernate.generate_statistics`로 고정한다(`*QueryScaleTest`, `NotificationListScaleTest`).

## 패키지 구조

`com.platform.wikibackend.<도메인>` 수직 분할. 페이지 쪽은 2026-10-18에 나눴다:

| 클래스 | 맡는 것 |
|---|---|
| `page/PageService` | 생성·조회·수정(새 버전)·공동 초안 게시·휴지통 보내기·게시·`getEditable` |
| `page/PageCopyService` · `PageMoveService` · `PageMetaService` · `RevisionService` | 복사 · 이동/재정렬 · 소유자/검증/아이콘/공유/조회수 · 이력/복원 |
| `page/PageStructure` | 존재 확인·행 잠금·서브트리 수집·형제 순번·부모 검증(공용) |
| `page/PageWriteGuard` | 보관 불변식 — 버전을 올리거나 메타를 바꾸는 **모든** 경로가 부른다 |
| `page/PageDataPurger` | 되돌릴 수 없는 삭제의 딸린 데이터(첨부·지난 버전 객체·리액션·Yjs 상태 등) — 영구 삭제·스페이스 삭제 공용 |
| `page/TrashService` | `@SQLRestriction("deleted_at is null")` 우회는 **여기서만**(휴지통 포함 네이티브 질의) |
| `permission/EffectivePermissionService` | 페이지 단위 판정의 단일 함수(VIEW/EDIT 제한 + 초안 정책). 본문·트리·검색·알림·첨부·댓글이 전부 여기를 탄다 |
| `notification/NotificationVisibility` | 알림함·요약 메일의 발송 시점 재판정 |

FQCN 대신 import를 쓴다. 새 쓰기 경로를 만들면: `PageWriteGuard` → 권한(`spaces.require` + `effective.requireEdit`) →
변경 → 리비전·`taskSync.sync`·`labelService.reindexLinks` → 이벤트(`events.afterCommit`) → 알림 순서를 따른다.

## 확정 규약(이 리포)

- **오류 계약·JWT 검증·공용 예외는 `common-starter`**(`com.platform.common.error.*`) — 서비스에 다시 복제하지 않는다.
- **장애 전파**: org gRPC는 모든 클라이언트가 `platform.org-grpc.deadline-seconds` 데드라인을 건다.
  UNAVAILABLE/DEADLINE → 503, 그 외 → fail-closed. 목록 필터에서 org 불능을 빈 결과로 삼키지 않는다.
- **잠금 순서**: page 행 → `collaboration_document` 행. 버전을 올리는 경로는 page를 먼저 잠근다(`findByIdForUpdate`).
  게시 아닌 경로가 버전을 올리면 `CollaborationDraftReset.afterExternalWrite`로 공동 초안을 제자리 리셋한다.
- **낙관적 락**: 수동 `expectedVersion` 비교 → 409(엔티티 `@Version` 아님).
- **초안 노출**: 작성자 + 스페이스 ADMIN + 전역 ADMIN만(2026-10-18 사용자 결정). 판정은 Effective 한 곳.
- **보관 문서는 읽기 전용**(PageWriteGuard). 휴지통 복원 시 부모가 없거나·다른 스페이스·보관 중이면 루트로.
- **"지우고 다시 넣기" 교체**는 bulk `@Modifying` 삭제(즉시 실행) 또는 `flush()` 뒤에 넣는다 — 파생 `deleteBy`는
  flush 때 INSERT가 먼저 나가 운영 PG 유니크 제약에 걸린다.
- **LIKE**는 `LikePatterns`로 이스케이프하고 질의에 `escape '\'`를 붙인다.
- **PDF 렌더러는 외부 URL을 열지 않는다**(SSRF). 이미지는 위키 첨부만 저장소에서 읽어 data URI로 넣는다.
- **콘텐츠 gRPC(:9111)**는 무인증·search-service 전용 — `SEARCH_MODE=lite`면 뜨지 않는다. 서비스 간 토큰은 플랫폼 공통 작업.
- **이관 로직은 위키에 넣지 않는다** — 내부 import API(`/internal/wiki/import/**`, `X-Internal-Token`)만.

## Flyway 마이그레이션

- `src/main/resources/db/migration/V<n>__<설명>.sql`, PostgreSQL 16 기준. 적용된 파일은 절대 수정하지 않는다.
- 번호는 main 최신 + 1(병렬 브랜치는 머지 순서와 맞춘다). 시드 데이터 금지.
- 엔티티 enum을 늘리면 CHECK 제약도 새 마이그레이션으로 바꾼다(V2→V42 BLOG 결함 참고).
- 새 마이그레이션은 `PostgresTestSupport` 계열 테스트로 실제 적용·제약을 확인한다(H2는 마이그레이션을 타지 않는다).

## 주요 설정(`application.yml`)

| 키(환경 변수) | 뜻 |
|---|---|
| `platform.org-grpc.*` (`ORG_GRPC_HOST/PORT/DEADLINE_SECONDS`) | 권한·디렉터리 gRPC |
| `platform.grpc.enabled/port/bind-address` (`WIKI_GRPC_*`) + `platform.search.mode` (`SEARCH_MODE`) | 콘텐츠 gRPC 서버 |
| `platform.wiki.internal-token` (`WIKI_INTERNAL_TOKEN`) | 내부 import API 공유 비밀(비면 403) |
| `platform.wiki.org.internal-uri/token` (`ORG_INTERNAL_URI/TOKEN`) | 메일 허브. 토큰 비면 메일 채널 꺼짐 |
| `platform.wiki.storage.*` (`WIKI_S3_*`, `WIKI_STORAGE_WRITE_BACKEND`) | 첨부 저장소 |
| `platform.wiki.trash/revisions/attachment-reconciliation/mail` | 스케줄러(테스트에서는 꺼 둔다) |
| `platform.events.enabled` (`PLATFORM_EVENTS_ENABLED`) | Redis Streams 발행 |

포트: REST `:9110`(dev `:19110`), gRPC `:9111`. 전체 변수 표는 README.
