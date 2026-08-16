# wiki-backend

MSA_TEMPLATE의 **위키 정본 서비스**다. 스페이스와 계층형 페이지, 버전 이력, 첨부 파일을
관리한다. org-service에서 `SPACE` 권한을 확인하고, 변경 이벤트를 Redis Streams에 발행한다.
search-service는 내부 gRPC로 페이지·첨부 메타데이터를 가져가 검색 색인을 만든다.

전체 플랫폼 구성은 [infra-settings](https://github.com/chanho4702/infra-settings), 프론트 기능은
[wiki-front](https://github.com/chanho4702/WIKI)를 참고한다.

## 한눈에 보기

| 항목 | 내용 |
|---|---|
| 런타임 | Java 24 · Spring Boot 4.0.6 · Gradle |
| REST | `:9110` / dev `:19110` · `/api/wiki/**` |
| 내부 gRPC | `:9111` · `WikiContentService` |
| 데이터 | PostgreSQL `wikidb` · Flyway · 로컬 첨부 파일 저장소 |
| 인증·인가 | auth-server RS256 JWT 검증 + org-service `SPACE` grant |
| 이벤트 | Redis Streams `platform:events:v1` |

> dev 프로필에서도 gRPC는 `:9111`을 사용한다. REST만 운영 포트 +10000 규약에 따라
> `:19110`으로 이동한다.

## 빠른 시작

JDK 24와 GitHub Packages의 `common-proto:0.5.0`을 읽을 토큰이 필요하다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-24'
$env:GITHUB_TOKEN = (gh auth token)

.\gradlew.bat test
.\gradlew.bat bootJar
.\gradlew.bat bootRun   # REST :9110, gRPC :9111
```

실행에는 PostgreSQL `wikidb`, Redis, auth-server JWKS, org-service gRPC가 필요하다.

```powershell
docker compose -f ..\infra\keycloak\docker-compose.yml up -d postgres redis keycloak auth-server org-service
```

dev 오프셋 프로필은 `--args='--spring.profiles.active=dev'`를 붙인다. PostgreSQL과 Redis는
dev 설정을 사용하고, auth-server JWKS와 org-service gRPC도 각각 `:19000`, `:19131`로 바뀐다.

## API

모든 REST 엔드포인트는 Bearer JWT가 필요하며, 외부에서는 gateway-server를 통해 접근한다.

| 영역 | 메서드와 경로 | 권한 |
|---|---|---|
| 스페이스 | `GET/POST /api/wiki/spaces` | 목록은 접근 범위, 생성은 인증 |
| 스페이스 | `GET/PUT/DELETE /api/wiki/spaces/{id}` | VIEW / ADMIN / ADMIN |
| 페이지 | `POST /api/wiki/pages` | EDIT |
| 페이지 | `GET /api/wiki/pages/{id}` | VIEW |
| 페이지 트리 | `GET /api/wiki/spaces/{spaceId}/pages` | VIEW |
| 페이지 | `PUT/DELETE /api/wiki/pages/{id}` | EDIT |
| 게시 | `POST /api/wiki/pages/{id}/publish` | EDIT |
| 버전 | `GET /api/wiki/pages/{pageId}/revisions[/{version}]` | VIEW |
| 복원 | `POST /api/wiki/pages/{pageId}/revisions/{version}/restore` | EDIT |
| 첨부 | `POST/GET /api/wiki/pages/{pageId}/attachments` | EDIT / VIEW |
| 첨부 | `GET/DELETE /api/wiki/attachments/{id}` | VIEW / EDIT |

페이지 수정은 기존 행을 덮는 동시에 전체 스냅샷 revision을 남긴다. 요청의
`expectedVersion`이 현재 버전과 다르면 `409 Conflict`를 반환하며, 과거 버전 복원도 새 버전으로
기록해 이력을 보존한다. 부모 변경 시 자기 자손 아래로 이동하는 순환도 거부한다.

## 서비스 경계

```text
gateway-server ──REST/JWT──▶ wiki-backend ──JPA──▶ PostgreSQL
                                   │                └─ 첨부 메타데이터
                                   ├─파일──────────▶ 로컬 저장소
                                   ├─gRPC─────────▶ org-service (SPACE 권한)
                                   ├─XADD─────────▶ Redis Streams
                                   └◀─gRPC──────── search-service (색인 원문 조달)
```

- 권한은 `VIEW < EDIT < ADMIN`이다. org-service 장애 시 fail-closed하며, 권한 없음과
  서비스 불능을 구분한다.
- 이벤트는 정본 트랜잭션 커밋 이후 발행하고 본문을 싣지 않는다. Redis 발행 실패가 정본을
  롤백하지는 않으며 검색 색인은 재색인으로 복구한다.
- gRPC `WikiContentService`는 search-service 전용이고 컨테이너 외부에 공개하지 않는다.
- 첨부 파일은 UUID storage key로 저장한다. DB에는 메타데이터만 두며, 스페이스 삭제 시
  revision·첨부 메타데이터와 실제 파일을 함께 정리한다.

## 환경 변수

| 변수 | 기본값 | 용도 |
|---|---|---|
| `WIKI_DB_URL` | `jdbc:postgresql://localhost:5433/wikidb` | PostgreSQL 연결 |
| `WIKI_DB_USERNAME` / `WIKI_DB_PASSWORD` | `keycloak` / `keycloak` | DB 자격증명 |
| `AUTH_JWKS_URI` | `http://localhost:9000/.well-known/jwks.json` | JWT 공개키 |
| `PLATFORM_ISSUER` / `PLATFORM_AUDIENCE` | `http://localhost:9000` / `platform-api` | JWT 검증 계약 |
| `ORG_GRPC_HOST` / `ORG_GRPC_PORT` | `localhost` / `9131` | SPACE 권한 판정 |
| `WIKI_GRPC_ENABLED` / `WIKI_GRPC_PORT` | `true` / `9111` | 콘텐츠 조달 gRPC |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | 이벤트 스트림 |
| `EVENTS_ENABLED` | `true` | 이벤트 발행 on/off |
| `WIKI_FILES_DIR` | `./data/attachments` | 첨부 파일 저장 경로 |
| `WIKI_MAX_ATTACHMENT_MB` | `20` | 파일·요청 최대 크기(MB) |
| `EUREKA_URI` | `http://localhost:8761/eureka` | 로컬 서비스 등록 |

## 테스트와 배포

```powershell
.\gradlew.bat test      # REST·권한·revision·gRPC·이벤트·Flyway 검증
.\gradlew.bat bootJar   # build/libs/app.jar
docker build -t wiki-backend .
```

`FlywaySchemaValidationTest`는 Testcontainers PostgreSQL을 사용하므로 Docker가 필요하다.
`Dockerfile`은 런타임 전용이며 먼저 `bootJar`를 실행해야 한다. 컨테이너에서는 Eureka 등록을
끄고 gateway-server의 `WIKI_SERVICE_URI`와 내부 DNS로 연결한다.

## 디렉터리 구조

```text
src/main/java/com/platform/wikibackend/
├─ space/        스페이스 REST·서비스·DTO
├─ page/         페이지·revision REST와 도메인 로직
├─ attachment/   첨부 REST·로컬 파일 저장소
├─ permission/   org-service gRPC 권한 어댑터
├─ grpc/         search-service용 WikiContentService
├─ event/        커밋 이후 Redis Streams 발행
├─ domain/       Space·Page·PageRevision·Attachment 엔티티
├─ repository/   JPA 저장소
├─ security/     JWT audience 검증
└─ common/       예외·공통 응답 처리
```
