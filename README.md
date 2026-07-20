# wiki-backend

ALM·Wiki 플랫폼의 위키 코어 서비스. [chanho4702/platform-backend](https://github.com/chanho4702/platform-backend)의
`com.platform:common-proto`(GitHub Packages)를 소비한다 — 빌드 전 `GITHUB_TOKEN`(read:packages) 필요.

## 실행

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-24'
$env:GITHUB_TOKEN = (gh auth token)
.\gradlew.bat bootRun    # dev 모드 (:9110, Eureka 등록)
```

배포판은 infra/keycloak compose — docker 프로필(Eureka 미등록, DNS 직결).

> CI는 GH_PACKAGES_TOKEN secret으로 proto를 받는다 — gh 재로그인 시 secret 갱신 필요.

## 도메인

- **space / page(계층 트리) / page_revision(전체 스냅샷) / attachment(UUID 로컬 스토리지)**
- 저장 = 새 버전(`expectedVersion` 낙관 잠금, 충돌 409) · 복원 = 새 버전(이력 보존)
- 권한: org-service gRPC `CheckPermission`(SPACE VIEW<EDIT<ADMIN) + Caffeine 30s, org 불능 시 fail-closed 403
- 이벤트: `EventPublisher` → Redis Streams `platform:events:v1` (커밋 후, best-effort — 색인 정합은 Wave C 재색인)

## API

`/api/wiki/spaces` · `/api/wiki/pages` · `/api/wiki/pages/{id}/revisions` · `/api/wiki/pages/{id}/attachments` — 전부 게이트웨이(:8000) 경유, JWT 필수.
