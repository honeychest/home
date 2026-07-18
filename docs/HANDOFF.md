# HANDOFF — 일회성 작업 인수인계 (완료되면 이 파일을 비운다)

> 용도: recipe 처럼 상설 현황판(progress.html)이 없는 일회성 작업의 세션 간 인계.
> 새 세션(Claude·Codex 무관)은 이 파일이 비어 있지 않으면 먼저 읽고 이어서 작업한다.
> 완료 후에는 이 안내 블록만 남기고 내용을 지운다 (비대화 방지).

## 2026-07-18 사전 수동 [묶기] UI + 추천 정렬·상한 개편 (미커밋) + 사전 전수 재점검 DB 반영 완료
- **미커밋 작업 1 (프론트)**: DictionaryPage 에 오너 직접 [묶기](대표·멤버 행 모두) → 하단 시트에서 대표
  검색·선택. `dictionaryFilter.mergeCandidates` 신설 + vitest 4건. vitest 57·build 통과 확인됨.
- **미커밋 작업 2 (백엔드)**: 추천 섹션당 내 것 10+남의 것 10(구 5+5 계획 대체), 정렬 = 부족 개수 →
  임박 재료 사용 우선 → 동점 일일 셔플(시드=사용자+날짜). RecommendRules/RecommendController/
  RecommendRulesTest — recipe 테스트 125 통과. 단일 원본 = CONTEXT.md "추천" 절. **커밋·푸시 남음.**
- **DB 반영 완료(재실행 금지)**: Claude 가 `gikka.ingredient_dictionary` 265건 전수 재분류 — 오너 승인 후
  SQL 일괄 반영 완료. 결과 대표 117(주재료51·양념46·기본19·보류1)·멤버 148·미판정 0.
  상세는 `docs/recipe/progress.html` 2026-07-18 절.