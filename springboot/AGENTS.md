# springboot 작업 규칙 (모든 AI 에이전트 공통)

이 폴더의 파일을 수정하기 전에 읽는다. 절차 규칙(승인·커밋·점검)은 `/chs/chs-rules.md`.

## 가져다 쓸 것 — 패턴 카탈로그 (같은 일을 하는 코드를 새로 만들면 그게 결함이다)
지시할 때 키 이름으로 부를 수 있다 (예: "재고 알림은 pattern-queue-worker + pattern-failover-notify 로").
모범 실물은 살아있는 프로덕션 코드다 — 별도 예제 코드를 만들지 말고 실물을 열어 모방한다.

| 키 | 의도 (언제 쓰나) | 모범 실물 (recipe 는 `domain/recipe/` 아래) |
|---|---|---|
| pattern-pure-rules | 임계값·분류·매칭 등 순수 판정. 컨트롤러/저장소에 인라인 금지 — static 순수 함수 + 단위 테스트 | `registration/RegistrationRules.java`, `fridge/FridgeRepository.rankFrequent`, `registration/ExtractionResultJson.java` |
| pattern-rest-seam | 외부 HTTP 호출 — `RestClient.Builder` 주입으로 MockRestServiceServer 테스트 시임 확보 | `registration/GeminiRecipeExtractor.java` |
| pattern-port-adapter | 외부 시스템(AI·메타 조회 등) 인터페이스 격리 — 구현체 교체로 끝나게 | `RecipeExtractor` / `VideoMetadataClient` |
| pattern-failover-notify | 외부 의존이 막혔을 때 폴백 전환 + 텔레그램 알림 | `GeminiRecipeExtractor` 페일오버 + `GikkaTelegramNotifier` |
| pattern-queue-worker | DB 대기열 + 단일 워커 비동기 처리 (2인스턴스 중복 실행 안전) | `registration/RegistrationWorker.java` + `registration/GeminiRateLimiter.java` (호출 속도·백오프·생존 신호를 DB 원자적 UPDATE 로 조율 — 인스턴스 메모리에 두면 합산 한도를 못 지킨다) |
| pattern-tx-template | 보조 DB(gikka) 트랜잭션 — 스프링 빈 TransactionManager 등록 금지(아래 금지 참조) | `GikkaDataSourceConfig` 의 `gikkaTxTemplate` |
| pattern-raw-signal | 품질 경고 등 "증상" 표시 — 증상 하나짜리 좁은 컬럼(`has_xxx`, `xxx_warning`) 대신 원인이 될 원시 신호를 목록 컬럼에 저장, 경고 문구는 그 신호 조합을 보는 별도 순수 매핑 함수가 도출 | `registration/RegistrationRules.analysisSignals` + `video.analysis_signals` |

적립 규칙: 이번 작업에서 2곳 이상 쓰일 만한 구조를 만들었으면 커밋 전에 키를 붙여
이 표에 등록한다. 실물이 리팩토링되면 키는 유지하고 경로만 갱신. 10개를 넘으면
`springboot/docs/patterns.md` 로 분리하고 여기엔 링크만 남긴다.
사람용 교보재(흐름 설명·뼈대 코드)는 `frontend/src/page/admin/backendPatterns.js` 원장 —
admin > "백엔드 패턴"에서 열람. 패턴 등록·변경 시 표와 이 원장 두 곳을 함께 갱신한다.

## 금지
- **배포 대상 코드를 손으로 복사한 사본으로 돌리기** — 저장소만 갱신되고 사본은 그대로 남아
  조용히 어긋난다. 2026-07-16 실제 사고: mac-mini launchd 가 `~/gikka-local/server.py` 사본을
  돌아 품질 경고 기능이 **DONE 125건 전부 한 번도 작동 안 함**. 프로세스는 체크아웃 경로를
  직접 실행하고, 배포는 Jenkinsfile stage 로. 도커 밖 호스트 프로세스도 예외 없음
  (`gikka/README.md`·`docs/recipe/CONTEXT.md` "gikka 로컬 서비스 배포 — 사본 금지").
- `domain/recipe` ↔ 다른 패키지 상호 import (ArchUnit 이 빌드에서 차단 — 규칙 완화 금지.
  공용 코드가 필요하면 recipe 패키지 안으로 복사해 소유).
- recipe 쪽 TransactionManager 스프링 빈 등록 (부트 기본 자동 구성이 꺼져 기존 도메인
  @Transactional 전체가 깨짐 — 2026-07-10 실측). gikka 트랜잭션은 `gikkaTxTemplate` 로만.
- 새 `@Scheduled` 를 중복 실행 고려 없이 추가 (앱 2인스턴스 — 멱등성 또는 단일 실행 장치 필수).
- 특정 증상 하나만을 위한 좁은 단일 목적 컬럼 신설(`pattern-raw-signal` 위반) — 그 증상을
  낳는 원시 신호를 저장하고 판정은 순수 함수로 분리할 것.

## 서버 제약 (상세: `chs/server/**`, commit-check server-profile)
- 앱 2인스턴스 · 힙 512M(SerialGC) · Hikari 풀 4(배치 6) — 큰 인메모리 적재·긴 트랜잭션 주의.
- DDL 은 Flyway 마이그레이션으로만. prod 는 validate — 매핑 불일치 시 기동 실패.

## 도메인별
| 영역 | 따를 것 |
|---|---|
| `domain/recipe/**` | `docs/recipe/PLAYBOOK.md` 아키텍처 관례 절 — 필수 |

## 검증
- 커밋 전 `./gradlew test`. 한글 JSON body 를 셸 curl 로 보내면 CP949 로 깨짐 —
  UTF-8 파일 + `--data-binary @파일` 로 검증.
