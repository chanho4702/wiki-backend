# wiki-backend

[![CI](https://github.com/chanho4702/wiki-backend/actions/workflows/ci.yml/badge.svg)](https://github.com/chanho4702/wiki-backend/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-24-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.6-6DB33F?logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-wikidb-4169E1?logo=postgresql&logoColor=white)
![Redis Streams](https://img.shields.io/badge/Redis-Streams-DC382D?logo=redis&logoColor=white)

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
| 데이터 | PostgreSQL `wikidb` · Flyway · S3 호환 첨부 저장소(LOCAL 레거시 읽기 지원) |
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
| 공동 초안 확정 | `PUT /api/wiki/pages/{id}/collaboration-draft` | EDIT |
| 게시 | `POST /api/wiki/pages/{id}/publish` | EDIT |
| 버전 | `GET /api/wiki/pages/{pageId}/revisions[/{version}]` | VIEW |
| 복원 | `POST /api/wiki/pages/{pageId}/revisions/{version}/restore` | EDIT |
| 첨부 | `POST/GET /api/wiki/pages/{pageId}/attachments` | EDIT / VIEW |
| 첨부 확정 | `POST /api/wiki/pages/{pageId}/attachments/confirm` | EDIT |
| 첨부 | `GET /api/wiki/attachments/{id}[/inline]`, `DELETE /api/wiki/attachments/{id}` | VIEW / EDIT |
| 공동 편집 ticket | `POST /api/wiki/pages/{pageId}/collaboration-ticket` | EDIT |
| 댓글 | `GET/POST /api/wiki/pages/{pageId}/comments` | VIEW |
| 댓글 | `PUT/DELETE /api/wiki/comments/{id}` | 작성자 (삭제는 스페이스 ADMIN도) |

페이지 수정은 기존 행을 덮는 동시에 전체 스냅샷 revision을 남긴다. 요청의
`expectedVersion`이 현재 버전과 다르면 `409 Conflict`를 반환하며, 과거 버전 복원도 새 버전으로
기록해 이력을 보존한다. 부모 변경 시 자기 자손 아래로 이동하는 순환도 거부한다.

공동 초안 확정은 `expectedPageVersion`과 `expectedGeneration`을 모두 검사한다. page와
`collaboration_document` metadata를 같은 PostgreSQL transaction에서 row lock한 뒤 page revision과
generation을 함께 한 단계 전진시키므로, 동시 저장 중 하나만 성공하고 이전 세션의 늦은 요청은
`409 Conflict`로 끝난다. Yjs binary state 자체는 계속 collaboration-service만 읽고 쓴다.

댓글은 1단 답글까지 허용한다(답글의 답글은 400). 읽기·쓰기 모두 스페이스 VIEW 기준이며 —
org-service에 COMMENT action이 생기기 전까지의 기준선 — 수정은 작성자만, 삭제는 작성자 또는
스페이스 ADMIN(moderation)이 한다. 최상위 댓글을 지우면 답글도 함께 사라진다. `authorName`은
작성 시점 표시 이름 스냅샷이고, `updatedAt`은 본문이 실제로 수정된 시각이라 수정 전에는 null이다
(무변경 재저장은 "(수정됨)"을 남기지 않는다). `anchor_type`은 후속 인라인 댓글 확장 자리다.

### OpenAPI

springdoc이 컨트롤러 주석에서 OpenAPI 3 스펙을 만들어 `GET /v3/api-docs`(JSON)로 낸다. UI는
붙이지 않는다. 게이트웨이도 nginx도 `/v3`를 라우팅하지 않으므로 **클러스터 안에서만** 보이고,
공개 문서 인스턴스(`docs` 프로필)에서는 경로째로 막혀 스펙이 나가지 않는다.

```bash
docker run --rm --network <compose 네트워크> curlimages/curl -s http://wiki-backend:9110/v3/api-docs
```

주석 규약은 컨트롤러마다 `@Tag(영문 리소스명, 한국어 설명)`, 엔드포인트마다
`@Operation(summary)`, 뜻이 드러나지 않는 파라미터에 `@Parameter(description)`, DTO 핵심 필드에
`@Schema(description, example)`다.

공통 오류는 `OpenApiConfig`가 자동으로 붙인다(전부 스키마 `PlatformError`). 이 규칙은
alm-backend·org-service와 맞춘 플랫폼 공통 규칙이라 여기만 바꾸지 않는다.

| 코드 | 붙는 곳 |
|---|---|
| 401·403 | 모든 오퍼레이션 |
| 400 | 요청 본문(`@RequestBody` 또는 `MultipartFile`)이 있는 오퍼레이션 |
| 404 | 경로 변수로 대상을 지목하는 오퍼레이션 |
| 409 | `expectedVersion`을 받는 PUT |
| 503 | 모든 오퍼레이션 — 읽기·쓰기가 모두 org-service 권한 판정을 탄다 |

설명 문구 6종은 wiki·alm·org가 같은 문자열을 쓴다(`OpenApiConfig.ERROR_DESCRIPTIONS`).
400은 요청 본문을 받는 엔드포인트를 기준으로 붙인다 — 400이 날 수 있는 모든 경로의
완전한 목록은 아니다(쿼리 파라미터 형변환 실패 등은 이 표에 없다).

`/internal/**`은 `springdoc.paths-to-exclude`로 스캔에서 통째로 뺀다. 내부 전용 컨트롤러를
새로 만들어도 공개 문서로 새지 않으므로, 거기에는 `@Tag`·`@Operation`을 달 필요가 없다.
`OpenApiDocsTest`가 태그·요약 누락, 내부 경로 노출, 인증 주체 누출, 성공 응답 소실,
그리고 위 400·503 규칙을 회귀로 막는다.

사람이 읽는 문서 페이지는 이 스펙에서 생성한다 — myFront의 `scripts/api`가 스펙을 긁어
`docs/api-reference/`를 만들고 문서 위키로 동기화한다. 생성물을 직접 고치지 말고 여기 주석을 고친다.

### 내부 이관 API (`/internal/wiki/import`, W29 X1)

이관 엔진(migration-service)이 "원본 그대로" 문서를 넣기 위한 **내부 전용** 표면이다. 공개
`/api/wiki/**`로는 할 수 없는 일 — 원본 생성·수정 시각 보존, 지난 버전을 리비전 1..k로 깔기,
미대조 작성자 표시, 버전을 올리지 않는 본문 교체, 알림 없는 댓글·제한 — 을 한다.

- **노출 안 함**: 게이트웨이도 nginx도 `/internal/**`을 라우팅하지 않는다. 브라우저에서는 없는
  경로다. OpenAPI 스펙에서도 빠진다(`springdoc.paths-to-exclude`).
- **인증**: `X-Internal-Token`(= `WIKI_INTERNAL_TOKEN`) 공유 비밀. **비어 있으면 전부 403**이라
  이관을 쓰지 않는 인스턴스는 자동으로 닫혀 있다. 사용자 JWT를 쓰지 않는다.
- **주체**: `X-Actor-Id`(잡 요청자 id). 감사 기록과 `createdBy` 폴백에 쓰인다. 쓰기 요청에서
  없거나 숫자가 아니면 400.
- **권한 검사 없음**: 대상 스페이스 ADMIN 판정은 엔진이 org-service로 이미 끝냈다고 본다.
- **부수효과**: 검색 색인 이벤트(`pageCreated`/`pageUpdated`)만 발행한다. 알림·자동 구독은 없고,
  감사는 문서 한 건당 `IMPORTED` 한 줄만 남는다.

| 메서드 | 경로 | 본문 → 응답 |
|---|---|---|
| POST | `/pages` | `{spaceId, parentId?, type, title, content, createdAt, updatedAt, authorId?, importedAuthorName?, sourceUrl?, sortOrder?, labels[], revisions?}` → `{pageId, version, issues[]}` |
| PUT | `/pages/{id}` | `{title, content, updatedAt, editorId?, editorName, changeNote, sourceUrl?, labels[]}` → `{pageId, version, issues[]}` |
| PUT | `/pages/{id}/content` | `{content, bumpVersion, changeNote?}` → `{pageId, version, changed}` |
| PUT | `/pages/{id}/order` | `{sortOrder}` → `{pageId, sortOrder, changed}` |
| POST | `/pages/{id}/attachments` | multipart `file` + `filename`·`contentType`·`checksum`·`sourceVersion` → `{attachmentId, inlineUrl, downloadUrl, outcome}` |
| POST | `/pages/{id}/comments` | `{parentCommentId?, authorId?, authorName, body, createdAt}` → `{commentId}` |
| GET | `/comments/{id}` | → `{commentId, pageId, parentCommentId, createdAt}` (없으면 404) |
| PUT | `/pages/{id}/restrictions` | `{view:[{type,id}], edit:[...]}` → 204 |
| GET | `/pages/{id}` | → `{pageId, spaceId, parentId, title, type, contentLength, version, sortOrder, labels[], attachments[{id,filename,checksum}], commentCount}` |
| GET | `/spaces/{id}/pages?title=` | → `{pages:[{pageId, title, type}]}` (중복이면 여러 건) |
| GET | `/spaces/{id}` | → `{spaceId, key, name}` |

규칙 몇 가지가 계약의 일부다.

- `authorId`(댓글은 `authorId`, 재이관은 `editorId`)가 있으면 그 사람이 쓴 것이 되고, 없으면
  `X-Actor-Id`가 작성자로 눕고 원본 이름이 표시 스냅샷으로 남는다.
- `revisions`가 오면 리비전 1..k를 깔고 현재본이 k+1이 된다. 요청의 `version`은 **순서를 정할
  때만** 쓰이고 실제 번호는 서버가 1부터 다시 매긴다.
- `bumpVersion=false`는 버전을 올리지 않고 현재 리비전 본문까지 함께 눌러 이력과 현재를
  일치시킨다(첨부 URL fixup). `true`는 `changeNote`를 단 새 리비전을 만든다(링크 정리).
- 첨부 `outcome`은 같은 이름·같은 checksum이면 `UNCHANGED`(저장소에 쓰지 않는다), 같은
  이름·다른 내용이면 `NEW_VERSION`(같은 id로 갈아끼움), 그 외 `CREATED`다. `checksum`을 보내면
  서버가 실제 바이트와 대조해 다르면 400이다. multipart 상한은 일반 업로드와 같다
  (`WIKI_MAX_ATTACHMENT_MB`).
- 오류는 플랫폼 계약 `{"error": "메시지"}`, 상태 코드는 400/403/404 그대로다.

요청·응답 예시는 `src/test/resources/fixtures/import-api/*.json`에 있고
`WikiImportApiTest`가 그 파일을 실제 요청 본문으로 쓴다 — migration-service는 같은 픽스처로
가짜 위키 서버를 만든다. 픽스처의 `-1`은 "호출자가 실제 id로 채운다"는 자리 표시다.

## 서비스 경계

```text
gateway-server ──REST/JWT──▶ wiki-backend ──JPA──▶ PostgreSQL
                    (N개)          │                └─ 첨부 메타데이터
                                   ├─S3 API───────▶ 공유 오브젝트 저장소
                                   ├─gRPC─────────▶ org-service (SPACE 권한)
                                   ├─XADD─────────▶ Redis Streams
                                   └◀─gRPC──────── search-service (색인 원문 조달)
```

- 권한은 `VIEW < EDIT < ADMIN`이다. org-service 장애 시 fail-closed하며, 권한 없음과
  서비스 불능을 구분한다.
- 이벤트는 정본 트랜잭션 커밋 이후 발행하고 본문을 싣지 않는다. Redis 발행 실패가 정본을
  롤백하지는 않으며 검색 색인은 재색인으로 복구한다.
- gRPC `WikiContentService`는 search-service 전용이고 컨테이너 외부에 공개하지 않는다.
- 첨부 파일은 UUID storage key로 공유 S3에 저장하고 DB에는 backend·bucket·version·checksum을
  기록한다. 어느 위키 노드가 업로드했든 다른 노드가 같은 객체를 읽을 수 있다. 기존 `LOCAL` 행은
  레거시 볼륨에서 계속 읽으며, 스페이스 삭제 시 revision·첨부 메타데이터와 실제 객체를 함께 정리한다.
- 에디터 선업로드는 `PENDING`으로 저장한 뒤 페이지 본문 저장 후 확정한다. 저장 전에 이탈한 객체는
  스케줄러가 최신 본문 참조를 대조해 확정하거나 보존기간 뒤 제거한다.
- 공동 편집 WebSocket에는 Access Token을 query로 보내지 않는다. 기존 JWT로 EDIT 권한을 확인해
  60초 opaque ticket을 발급하고, Redis에는 원문이 아닌 SHA-256 key와 v1 payload만 TTL로 저장한다.
  collaboration service는 `GETDEL`로 ticket을 원자적으로 한 번만 소비한다. payload 계약은
  `schema/collaboration-ticket-v1.schema.json`이 정본이다.

## 공개 문서 인스턴스(docs 프로필)

같은 이미지를 `SPRING_PROFILES_ACTIVE=docker,docs`로 한 번 더 띄우면 **로그인 없이 읽기만 되는**
두 번째 인스턴스가 된다(별도 `docsdb`). 팀 위키와 프로세스·DB·이벤트가 전부 분리된다.

- 인증 없음 — `DocsSecurityConfig`가 `SecurityConfig`를 대체하고, `DocsPrincipalFilter`가 모든
  요청에 합성 주체를 심는다(익명 `sub=0`, 임포터 `sub=1`). 컨트롤러가 예외 없이
  `jwt.getSubject()`를 파싱하기 때문이다. `sub=0`은 org-service에 없는 ID다.
- 경로 인가는 기본 거부다: `OPTIONS` · `GET /api/wiki/**` · `POST /graphql`만 열고,
  `X-Docs-Import-Token`이 맞은 요청에만 `/api/wiki/**` 쓰기를 연다. 나머지는 403
  (`{"error": "읽기 전용 문서 인스턴스입니다."}`). 조회수 기록은 임포터에게도 닫혀 있다.
- 권한 판정은 org-service를 부르지 않는다 — `PublicReadPermissionClient`가 VIEW를 항상 허용하고
  그 위 등급은 임포터에게만 준다. gRPC 채널·Eureka·색인 gRPC·이벤트 발행·스케줄러 5종은 꺼진다.
- `DOCS_IMPORT_TOKEN`이 비어 있으면 임포트 경로도 닫혀 어떤 경로로도 쓰기가 되지 않는다.
- 이 인스턴스의 호스트 포트는 루프백에만 연다(임포터 전용). 웹 노출은 nginx `/api/docs/`가 맡고
  거기서 `X-Docs-Import-Token` 헤더를 제거한다.

## 이관(마이그레이션)은 이 서비스에 없다

Confluence Data Center·Notion에서 문서를 가져오는 엔진은 **migration-service**에 있다(W29 X4).
Document IR 스키마·정규화기·DC 클라이언트·잡 워커·보고서·`/api/migration/**` REST와 그 운영
가이드가 전부 그쪽 리포와 README로 옮겨 갔다. 위키에 남은 것은 "옮겨온 데이터를 원본 그대로 받아
넣는" 내부 쓰기 표면 하나뿐이다 — 위의 [내부 이관 API](#내부-이관-api-internalwikiimport-w29-x1).

가른 이유는 경계다. 이관은 남의 서버 사정(인증·속도 제한·버전)에 끌려다니는 장기 실행 잡이고,
위키는 그 사정을 몰라야 한다. 엔진이 위키 안에 있으면 DC 한 곳의 응답 지연이 위키 배포 주기와
스키마에 묶인다.

- 잡 원장 테이블(`migration_*`)은 V37에서 지웠다. 위키가 갱신하지 않는 원장을 들고 있으면
  엔진 쪽 원장과 갈라진 사본이 된다.
- `page.imported_author_name` / `imported_source_url`은 남는다. 잡 원장이 아니라 문서 자신의
  속성이고("원본 작성자를 우리 계정에서 못 찾았다"), import API가 지금도 채운다.
- 관리 화면은 wiki-front `/admin/migrations`(전역 관리자)에 그대로 있고, 호출 대상만
  `/api/migration/**`으로 바뀌었다.

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
| `WIKI_STORAGE_WRITE_BACKEND` | `local` | 신규 첨부 저장소(`local` / `s3`) |
| `WIKI_S3_ENABLED` / `WIKI_S3_BUCKET` | `false` / `wiki-attachments` | S3 저장소 활성화·버킷 |
| `WIKI_S3_REGION` / `WIKI_S3_ENDPOINT` | `ap-northeast-2` / 빈 값 | S3 리전·호환 endpoint |
| `WIKI_S3_PATH_STYLE_ACCESS` | `false` | S3Mock·일부 호환 저장소 path-style |
| `WIKI_S3_ACCESS_KEY` / `WIKI_S3_SECRET_KEY` | 빈 값 | S3 요청 서명 자격증명 |
| `WIKI_FILES_DIR` | `./data/attachments` | 기존 LOCAL 첨부 읽기 경로 |
| `WIKI_MAX_ATTACHMENT_MB` | `20` | 파일·요청 최대 크기(MB) |
| `WIKI_PENDING_ATTACHMENT_RETENTION` | `PT24H` | PENDING 첨부 정리 유예 |
| `WIKI_COLLABORATION_TICKET_TTL` | `PT1M` | WebSocket 접속용 1회 ticket TTL(최대 5분) |
| `EUREKA_URI` | `http://localhost:8761/eureka` | 로컬 서비스 등록 |
| `WIKI_EUREKA_ENABLED` | `true`(docker) | Compose 다중 노드 REST 로드밸런싱 등록 |
| `DOCS_IMPORT_TOKEN` | 빈 값 | docs 프로필 임포터 토큰. 비면 쓰기 경로 전면 차단 |
| `WIKI_INTERNAL_TOKEN` | 빈 값 | 내부 이관 API(`/internal/wiki/import`) 공유 비밀. 비면 그 경로 전면 차단 |

## 테스트와 배포

```powershell
.\gradlew.bat test      # REST·권한·revision·gRPC·이벤트·Flyway 검증
.\gradlew.bat bootJar   # build/libs/app.jar
docker build -t wiki-backend .
```

`FlywaySchemaValidationTest`와 S3 다중 노드 저장소 통합 테스트는 Testcontainers를 사용하므로
Docker가 필요하다. `Dockerfile`은 런타임 전용이며 먼저 `bootJar`를 실행해야 한다. Compose에서는
각 위키 인스턴스가 Eureka에 등록되고 gateway-server의 `lb://wiki-backend`가 REST 요청을 분산한다.

## 디렉터리 구조

```text
src/main/java/com/platform/wikibackend/
├─ space/        스페이스 REST·서비스·DTO
├─ page/         페이지·revision REST와 도메인 로직
├─ attachment/   첨부 REST·LOCAL/S3 저장소·PENDING 수명주기
├─ collaboration/ 단기 WebSocket ticket 발급·Redis v1 계약
├─ importapi/    이관 엔진이 부르는 내부 쓰기 API(/internal/wiki/import)
├─ permission/   org-service gRPC 권한 어댑터
├─ grpc/         search-service용 WikiContentService
├─ event/        커밋 이후 Redis Streams 발행
├─ domain/       Space·Page·PageRevision·Attachment 엔티티
├─ repository/   JPA 저장소
├─ security/     JWT audience 검증
└─ common/       예외·공통 응답 처리
```
