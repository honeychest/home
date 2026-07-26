# gikka 작업 규칙 (모든 AI 에이전트 공통)

이 폴더의 파일을 수정하기 전에 읽는다. 절차 규칙(승인·커밋·점검)은 `/chs/chs-rules.md`.
도메인 배경·결정 이력은 `docs/recipe/CONTEXT.md` 가 단일 원본 — recipe(기까) 작업은 거기서 시작한다.

## 이 프로젝트가 무엇인가
`springboot/` 에서 **완전히 분리한 독립 Spring Boot 앱**이다 (2026-07-26 이관, CONTEXT 18절).
멀티모듈이 아니라 별도 Gradle 프로젝트이고, 별도 이미지·별도 컨테이너·별도 Jenkins stage 로 배포된다.
분리한 목적은 하나다 — **배포가 서로 영향을 주지 않게.** `springboot/` 를 고친다고 기까가 내려가면 안 되고,
그 반대도 안 된다. 이 목적을 깨는 변경(공유 모듈 신설, 서로의 코드 import, 같은 이미지로 묶기)은 금지다.

## 가져다 쓸 것 — 패턴 카탈로그
`springboot/AGENTS.md` 의 표와 **같은 키를 쓴다**(pattern-pure-rules · pattern-rest-seam ·
pattern-neutral-zone · pattern-port-adapter · pattern-failover-notify · pattern-queue-worker ·
pattern-tx-template · pattern-raw-signal). 모범 실물이 대부분 이쪽으로 넘어왔으므로 경로만 다르다 —
`domain/recipe/` 접두어를 떼고 `com.chs.gikka.` 아래에서 같은 이름을 찾으면 된다.
패턴을 새로 적립하면 두 파일의 표를 함께 갱신한다(사람용 교보재는
`frontend/src/page/admin/backendPatterns.js` 원장).

## 금지
- **`springboot/` 와 코드를 주고받기.** 어느 방향이든 import·공유 모듈·심볼릭 링크 전부 금지.
  같은 일이 양쪽에 필요하면 각자 소유한다(분리 규율 7 — 그게 이 분리의 전제였다).
- **패키지 안쪽 방향 위반** — `dictionary` → `registration`, `external` → gikka 의 아무 패키지.
  둘 다 `GikkaArchitectureTest` 가 빌드에서 차단한다. 규칙을 완화하지 말고 import 를 지울 것.
  여러 패키지가 같은 걸 필요로 하면 `external` 중립 지대로(pattern-neutral-zone).
- **TransactionManager 스프링 빈 등록 금지.** 트랜잭션은 `GikkaDataSourceConfig` 의
  `gikkaTxTemplate` 로만 (springboot 시절 실측 사고의 유산이지만, 규율 자체는 유지 —
  DataSource 가 하나뿐인 앱에서 매니저를 따로 등록하면 자동 구성과 엇갈린다).
- **배포 대상 코드를 손으로 복사한 사본으로 돌리기.** 2026-07-16 실제 사고: mac-mini launchd 가
  `~/gikka-local/server.py` 사본을 돌아 품질 경고가 DONE 125건 전부 미작동.
  프로세스는 체크아웃 경로를 직접 실행하고 배포는 Jenkinsfile stage 로 (`gikka-extractor/README.md`).
- **의존성을 습관적으로 추가하기.** 이 앱은 recipe 가 실제로 쓰는 것만 담아 가볍다 —
  JPA·Redis·Lombok·Kafka·RabbitMQ·jasypt·bean validation **사용처가 하나도 없다**.
  DB 접근은 `JdbcClient` 하나뿐. 새 의존성은 "정말 필요한가"를 먼저 확인할 것.

## 환경 구분
| | local | prod |
|---|---|---|
| 활성화 | 기본값 (`SPRING_PROFILES_ACTIVE` 없으면 local) | 도커 `env_file` 의 `SPRING_PROFILES_ACTIVE=prod` |
| 설정 파일 | `application-local.properties` | `application-prod.properties` |
| 포트 | 8090 (springboot 8080 과 겹치지 않게) | 8080 (도커가 호스트 포트로 매핑) |
| DB | Tailscale IP 로 직접 (`100.69.229.3:5432`) | 컨테이너명 (`chs-pgvector:5432`) |
| 호스트 서비스 | Tailscale IP | `host.docker.internal:8765` |
| 인증 | `gikka.auth.dev-user-email` 로 구글 로그인 우회 | 실제 구글 로그인 (**dev-user-email 절대 금지** — 무인증 구멍) |

기본값을 prod 가 아니라 local 로 둔 이유: 프로파일이 실수로 빠졌을 때 운영 DB 자격증명이 없어
그냥 못 뜨는 편이, 로컬 개발기가 조용히 운영 설정으로 도는 것보다 안전하다.

`.env` 는 **로컬 전용이고 커밋하지 않는다**(`.gitignore` 처리됨). 운영은 mac-mini 의 도커
`env_file` 로 주입한다. 환경변수 이름은 분리 전과 같다 — `GIKKA_DB_USERNAME` ·
`GIKKA_DB_PASSWORD` · `GIKKA_GEMINI_API_KEY` · `GIKKA_YOUTUBE_API_KEY` · `TELEGRAM_TOKEN` ·
`TELEGRAM_CHATID` · `SPRING_PROFILES_ACTIVE`.

## 병행 기간 주의 (`springboot/` 에서 recipe 가 아직 안 지워진 동안)
분리는 코드 이관까지만 끝났고, 배포 전환·옛 코드 삭제가 남았다(`docs/HANDOFF.md` 2~4단계).
그동안:
- **recipe 코드 수정은 여기(`gikka/`)에만.** `springboot/domain/recipe/**` 는 곧 지울 사본이다.
- **gikka DB 마이그레이션을 추가하지 말 것.** 두 앱이 같은 `gikka` DB 에 각자의 Flyway 로 붙어
  있어서, 새 파일을 여기에만 넣으면 DB 이력에는 적용되고 `springboot/` 의 locations 에는 없어
  **다음 백엔드 배포 때 springboot 앱이 기동 실패**한다(`applied migration not resolved locally`).
  꼭 필요하면 같은 파일을 양쪽 폴더에 동시에 넣는다.

## 서버 제약
- **인스턴스 1개**(springboot 는 2개 블루그린). 그래도 `@Scheduled` 를 중복 실행 고려 없이
  추가하지 말 것 — 나중에 2인스턴스로 늘릴 때 조용히 깨진다. 등록 워커가 쓰는
  `claimNext` 의 `SKIP LOCKED` + `GeminiRateLimiter` 의 DB 원자적 UPDATE 가 모범이다.
- DDL 은 Flyway 마이그레이션(`db/migration/gikka/`)으로만.
- Gemini 무료 등급: 일 약 250~1,500요청 · 분당 약 10요청. `gikka.llm.min-interval-seconds` 로 보호.

## 검증
- 커밋 전 `./gradlew test` (이 폴더에서). 현재 187개.
- 한글 JSON body 를 셸 curl 로 보내면 CP949 로 깨짐 — UTF-8 파일 + `--data-binary @파일` 로 검증.
- 로컬 기동 시 **운영 등록 큐를 집어가지 않게** 워커를 끄려면
  `--spring.task.scheduling.enabled=false` (같은 gikka DB 를 보기 때문).
