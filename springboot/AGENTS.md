# springboot 작업 규칙 (모든 AI 에이전트 공통)

이 폴더의 파일을 수정하기 전에 읽는다. 절차 규칙(승인·커밋·점검)은 `/chs/chs-rules.md`.

## 가져다 쓸 것 — 같은 일을 하는 코드를 새로 만들면 그게 결함이다
- 순수 판정(임계값·분류·매칭 등)은 컨트롤러/저장소에 인라인하지 말고
  static 순수 함수 + 단위 테스트로. 모범: `domain/recipe/registration/RegistrationRules.java`,
  `domain/recipe/fridge/FridgeRepository.rankFrequent`, `GeminiRecipeExtractor.parseEnvelope`.
- 외부 HTTP 는 `RestClient.Builder` 주입으로 만들어 MockRestServiceServer 테스트 시임 확보.
  모범: `domain/recipe/registration/GeminiRecipeExtractor.java`.
- 외부 시스템(AI·메타 조회 등) 호출은 인터페이스 뒤에 격리 — 구현체 교체로 끝나게.
  모범: `RecipeExtractor` / `VideoMetadataClient`.

## 금지
- `domain/recipe` ↔ 다른 패키지 상호 import (ArchUnit 이 빌드에서 차단 — 규칙 완화 금지.
  공용 코드가 필요하면 recipe 패키지 안으로 복사해 소유).
- recipe 쪽 TransactionManager 스프링 빈 등록 (부트 기본 자동 구성이 꺼져 기존 도메인
  @Transactional 전체가 깨짐 — 2026-07-10 실측). gikka 트랜잭션은 `gikkaTxTemplate` 로만.
- 새 `@Scheduled` 를 중복 실행 고려 없이 추가 (앱 2인스턴스 — 멱등성 또는 단일 실행 장치 필수).

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
