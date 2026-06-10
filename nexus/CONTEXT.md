# nexus — CONTEXT

> 이 문서는 코드·그래프(GitNexus)가 줄 수 없는 것만 담는다 — 도메인 의미,
> 불변 규칙, 설계 '이유', 지향. 모듈 호출관계·실행 흐름은 GitNexus가 항상
> 최신으로 가지고 있으니 여기에 손으로 중복 기록하지 않는다.

## 1. 정의
텔레그램 1인용 개인비서 봇. 영어 학습(단어·문법 간격반복) + 생산성(할일·인박스·
루틴 알림) + 링크 스크랩 + 법령 검색을 한 봇에서 처리한다.
저장은 Notion, 휘발 상태는 Redis, AI는 로컬 LM Studio.

## 2. 핵심 제약
오너는 비개발자이고 코드 전체가 AI로 작성됐다.
⇒ 이 코드베이스의 1급 제약은 "오너가 동작을 이해할 수 있고, AI 에이전트가
   안전하게 손댈 수 있는가"다. 설계·리뷰 판단(단순성·명시적 주석·작은 모듈)은
   이 제약을 우선한다.

## 3. 모듈 지도 (의미 라벨 — 상세 호출관계는 GitNexus가 가짐)
  학습    복습덱(Review Deck): 단어·문법의 '간격반복'을 담는 단일 개념. 단어/문법은
          설정만 다른 두 Adapter다(간격표·졸업규칙·도메인필드만 차이). 등록·due 조회·
          채점(단계 전진+리뷰일 재계산)이 핵심 인터페이스.
          ※ 현재는 notion_service·grammar_service에 두 벌로 분산 → 복습덱으로 통합 진행 중.
          관련: word_repository · quiz_flow · quiz_schedule · schedule_plan · conversation_router
  생산성  todo_service · schedule_reminder_service · inbox_action_token ·
          ai_notion_control
  수집    url / youtube / github / jina / webpage_service  → Notion
  기타    law_service · ai_service / model_runner(LM Studio) · prompts
  공통    main · scheduler · session / redis_client · timeutil(KST 단일 기준) ·
          handlers/*(명령·콜백 라우팅)

## 4. 불변 규칙(invariant)
  - "하루"의 기준은 KST 자정. 퀴즈 카운트·세션 TTL은 KST 자정에 소멸(timeutil).
  - 자동 출제는 09/15/22시(KST) 3회. 하루 자동퀴즈 한도 20개(DAILY_QUIZ_LIMIT).
  - 간격반복 단계로 출제하며 졸업 단계(6) 도달 시 출제에서 제외.

## 5. 설계 결정과 이유 (코드엔 결과만 있고 '왜'는 없다)
  - 출제 시각 09/15/22: 근거 약함("아침/점심/저녁의 한가한 때"). 데이터로 재조정 여지.
  - 저장소 Notion: 일정관리 겸용 + GUI로 직접 확인/수정이 편해서.
  - AI 로컬(LM Studio): 클라우드 비용 절약, 작업 난도가 낮아 로컬로 충분.
  - 시간대 KST 고정: 원래는 서버 tz를 따랐고(AWS=UTC 시절 일부 데이터가 UTC로 저장됨),
    집 mac-mini로 이전하며 KST가 됐다. 배포 위치라는 우연에 '하루'를 묶지 않으려
    timeutil로 KST 단일 고정. 기존 UTC 저장분은 미마이그레이션(선택1). → ADR-0001
  - 간격반복 설계: '1-3-7 기법'에서 출발. 7일 뒤 망각·장기 유지 확인 위해 30일,
    60~120일(랜덤)까지 확장 후 졸업. (단어 1/3/7/30/60~120, 문법 1/3/7)
    인터넷 기법 기반이라 본인 적합도는 미검증 — 간격은 실험적.

## 6. 지향
  단순 할일 나열을 넘어 '라이프사이클 플래너'로 발전 — 일과표 + 알림 + 회고를
  묶어, 회고 결과로 다음날·다음주 계획을 새로 세우는 순환 구조.
  설계 시 이 방향과 충돌하는 결정은 피한다. (기능 추가는 구조 안정화 이후)
