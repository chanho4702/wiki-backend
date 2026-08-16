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

페이지 수정은 기존 행을 덮는 동시에 전체 스냅샷 revision을 남긴다. 요청의
`expectedVersion`이 현재 버전과 다르면 `409 Conflict`를 반환하며, 과거 버전 복원도 새 버전으로
기록해 이력을 보존한다. 부모 변경 시 자기 자손 아래로 이동하는 순환도 거부한다.

공동 초안 확정은 `expectedPageVersion`과 `expectedGeneration`을 모두 검사한다. page와
`collaboration_document` metadata를 같은 PostgreSQL transaction에서 row lock한 뒤 page revision과
generation을 함께 한 단계 전진시키므로, 동시 저장 중 하나만 성공하고 이전 세션의 늦은 요청은
`409 Conflict`로 끝난다. Yjs binary state 자체는 계속 collaboration-service만 읽고 쓴다.

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

## 마이그레이션 기반

Notion과 Confluence Data Center에서 가져온 문서는 provider별 원본을 곧바로 `Page.content`에
저장하지 않는다. 먼저 `schema/document-ir-v1.schema.json`의 provider 중립 Document IR로
정규화하고 `DocumentIrValidator`의 런타임 문법·의미 검증을 통과해야 한다.

- schema의 버전·provider·block/mark type·ID/checksum 규칙을 런타임과 공유한다.
- block ID와 media ID 중복, 선언되지 않은 media 참조, 만료 URL 직접 저장을 거부한다.
- 지원하지 않는 원본 구조는 `opaque + sourceRef`로 보존하며 원본 payload는 IR 밖에 둔다.
- 검증 오류는 stable code와 JSON path만 반환하고 문서 본문이나 원본 값을 반사하지 않는다.
- `migration_job`과 `migration_item`은 extract → normalize → media copy → resolve → verify
  checkpoint, retry 시각·횟수, dead letter 상태를 PostgreSQL에 보존한다.
- 외부 object mapping은 긴 원본 ID 대신 source identity의 SHA-256 key로 멱등성을 보장하고,
  `migration_issue`에는 구조화된 code/path만 기록한다.

Notion은 `snapshotVersion: 1` envelope에 page 응답과 parent block ID별 paginated
`Retrieve block children` 원본 응답을 함께 보존한다. normalizer는 현재 `Notion-Version: 2026-03-11`
계약을 명시적으로 확인하고 pagination·재귀 children 누락을 부분 import로 진행하지 않는다. rich text,
heading, paragraph, list/task, panel, column, page link와 복사 완료 media를 IR로 바꾸며, 미지원 block과
복사되지 않은 media는 `opaque + migration issue`로 보존한다. Notion-hosted 임시 URL은 IR에 복사하지
않는다. 근거는 [page content API](https://developers.notion.com/guides/data-apis/working-with-page-content)와
[file retrieval guide](https://developers.notion.com/guides/data-apis/retrieving-files)를 따른다.

Confluence DC는 특정 제품 버전을 아직 보장하지 않고, 공식 storage format의 공통 XML 부분집합만
fixture parser로 검증한다. heading/paragraph/mark/list/task/panel/layout/table/page link를 IR로 바꾸고,
복사 완료 attachment image만 `mediaId`로 연결한다. custom macro와 미지원 element는
`opaque + sourceRef + issue`로 남긴다. XML parser는 DOCTYPE, entity, processing instruction과 외부
DTD/schema 접근을 차단한다. 근거는 [Atlassian storage format](https://confluence.atlassian.com/doc/confluence-storage-format-790796544.html)을
따르며 실제 고객/사내 DC 버전을 확보한 뒤 별도 compatibility matrix를 만든다.

현재 경계는 IR v1 golden fixture, migration checkpoint 저장 모델, Notion snapshot normalizer,
Confluence 공통 storage parser까지 검증한다. 실제 provider extractor와 media copier, worker/API는 이
경계 위에 순차적으로 추가하며, 기존 `Page.content` 정본 포맷은 바꾸지 않는다.

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
├─ migration/    Document IR 검증과 단계적 외부 문서 가져오기 기반
├─ permission/   org-service gRPC 권한 어댑터
├─ grpc/         search-service용 WikiContentService
├─ event/        커밋 이후 Redis Streams 발행
├─ domain/       Space·Page·PageRevision·Attachment 엔티티
├─ repository/   JPA 저장소
├─ security/     JWT audience 검증
└─ common/       예외·공통 응답 처리
```
