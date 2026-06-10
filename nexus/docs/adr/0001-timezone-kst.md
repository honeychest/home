# ADR-0001: 시간대를 KST 도메인 고정으로 통일

- 상태: 채택 (Accepted)
- 날짜: 2026-06-10

## 맥락 (Context)
nexus는 시간 기준이 세 갈래로 섞여 있었다.
- `datetime.now()` (naive) — 서버 로컬 tz를 따름. session/redis의 자정·TTL 계산.
- `datetime.now(timezone.utc)` (명시적 UTC) — notion/grammar의 리뷰일·등록일 저장.
- `timezone(timedelta(hours=9))` (KST) — scheduler/todo/reminder가 각자 정의.

원래는 "서버 tz를 따른다"는 암묵 방침이었다. 운영 서버가 AWS Linux(UTC)였기 때문에
naive 시각이 UTC가 됐고, 명시적 UTC 저장과 맞물려 일부 데이터가 UTC로 쌓였다.
이후 집 mac-mini(KST)로 이전하면서 naive 시각은 KST가 됐다.

문제: (1) "하루"의 정의가 배포 위치라는 우연에 묶인다. (2) 같은 코드베이스 안에서
단어 due 판정(UTC)과 할일 today(KST)가 서로 다른 '오늘'을 써 일관성이 깨진다.

## 결정 (Decision)
시간 기준을 **KST 도메인 고정**으로 통일한다.
- `timeutil` 모듈을 단일 출처로 두고(`KST`, `now_kst`, `today_kst`,
  `seconds_until_kst_midnight`), 모든 시각·날짜·자정 계산을 여기로 모은다.
- 서버 tz가 무엇이든(클라우드 복귀·이동 등) "하루"는 한국의 하루로 고정된다.
- 기존 UTC로 저장된 단어/문법 리뷰일은 **마이그레이션하지 않는다(선택1)**.
  신규 저장분부터 KST를 적용한다.

## 결과 (Consequences)
- 장점: 배포 위치와 무관하게 동작 일관. 코드 내 시간 혼용 제거. TTL이 KST 자정 보장.
- 잔여 리스크: AWS 시절 UTC로 저장된 리뷰일과 신규 KST 저장이 경계 날짜(자정~오전9시
  부근)에서 최대 1일 어긋날 수 있다. date 전용·매일 리뷰라 단어가 재출제·재저장되며
  자연 치유된다. 신경 쓰이면 일괄 마이그레이션을 별도 결정으로 추가한다.
- 되돌리기: 마이그레이션이 필요해지면 이 ADR을 뒤집는 후속 ADR을 추가한다.

## 관련
- `nexus/timeutil.py`, CONTEXT.md §5
- 커밋 f311b5b(시간대 KST 통일), 047d599(죽은 코드 정리)
