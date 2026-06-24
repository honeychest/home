## 먼저 읽을 것
- `docs/generated/log.md` (전 이력) 과 `docs/generated/index.md` (문서 카탈로그·관계맵·lint).
- 프로젝트 규칙: `/chs/chs-rules.md`, `CLAUDE.md`(GitNexus 사용 규칙). 로컬 개발 지침의 확인 형식과 승인
  규칙(쓰기·삭제·편집·생성은 사용자 응답에 `승인` 문자열 있을 때만)을 지킬 것.

## 지금까지 한 일 (완료)
- `docs/generated/` 위키 22개 재작성 + 용어집 보강 + 소스 검증. 중복 `chatbot.md` 삭제(→`be-chatbot.md` 단일화).
  `index.md`/`log.md` 신설.
- doc 레이어 재색인 완료(현재 **113청크**). 용어집(`domain-glossary.md`)·`be-chatbot.md` 정정분까지 모두 반영됨.
- **[1번] pageBoost를 docs/generated 위키에 적용 완료**: `PageContextRegistry.PageInfo`에 `boostPrefixes` 필드
  신설(하위호환 4-arg 생성자 유지), 9개 페이지에 짝 위키 경로를 넓게 매핑. `EvidenceRetriever`가
  `pathPrefixes ∪ boostPrefixes`로 가중. `pathPrefixes`는 불변이라 도메인 재색인 범위는 영향 없음(책임 분리).
  적용은 **앱 재시작만**(재색인 불필요). 빌드/테스트/`detect_changes` 검증 통과.
- `be-chatbot.md`의 PageInfo 설명을 5-arg로 정정.

## RAG 파이프라인 핵심 (오해 방지)
- 3단계: ①청킹(코드/CPU, 모델 아님) → ②임베딩(텍스트→벡터, **전체 재색인 시 여기가 ~3시간**, 로컬 LM Studio)
  → ③챗(검색청크로 답 생성, gemma).
- 모델 2종: **임베딩모델**(②, 색인·질문 양쪽이 같은 모델이어야 함) ↔ **챗모델**(③, gemma/Claude 자유 교체).
- 챗모델 교체(③→Claude): 답변 품질·속도 개선, **재색인 불필요**. 임베딩 교체(②): 좌표계가 바뀌어 **전체 재임베딩
  1회(3시간)+질문당 과금+코드 외부전송** 발생. (Claude는 임베딩 API 없음 → 임베딩은 OpenAI/Voyage 등 별도.)

## 핵심 사실 (재조사 불필요)
- 앱: `http://localhost:8080`. `/api/chat`은 permitAll → 평가용 직접 호출 가능.
  - 평가: `curl -s -X POST localhost:8080/api/chat -H "Content-Type: application/json; charset=utf-8"
    --data-binary @payload.json`
  - payload: `{"question":"...","pageId":"signal","sessionId":"eval","history":[]}` (한글은 UTF-8 파일로).
- **`/api/admin/**` 직접 호출 불가**(`SecurityConfig` JWT + `AdminIpInterceptor` 허용 IP). 재색인·상태조회는
  **사용자가 admin→test→Chatbot 탭 버튼**으로 실행(에이전트가 못 돌림).
- 재색인 3종: `/reindex`(전체, **~3시간**, 증분 아님 — DELETE 전체 후 전부 재임베딩),
  `/reindex/docs`(docs/generated만, 몇 분), `/reindex/domain/{domain}`(그 도메인 소스만).
  - **적용 시점 구분**: pageBoost·topK 등 검색 가중/파라미터는 **질문 시점 계산** → 앱 재시작만.
    excludePathPatterns·chunkStrategy·overlapTokens는 **색인 시점** → 재색인 필요.
- 색인 설정: `chatbot/config/ChatbotProperties.java` + `application.properties`(topK=6, overFetchMultiplier=4,
  pageBoost=0.15, chunkSize=512, minChunkSizeChars=350, overlapTokens=64, **chunkStrategy=SYMBOL_AWARE 이미 설정됨**).
  index-roots(application-local.properties): springboot/src, frontend/src, docs/generated, source-base=레포 루트.

## 남은 작업 (우선순위)
추천 순서: **3(평가셋) → 5/6 → (모델·인프라는 별도)**

1. **[운영] 평가셋 구축(3번)** — 지금 평가가 스팟체크뿐. `GET /api/admin/chatbot/logs/turns`(admin, 사용자 경유)로
   약한 질문 N개 추출 → 고정 회귀 평가셋. 5·6 효과를 숫자로 재기 위한 토대라 **먼저**.
2. **[설정] chunkStrategy=SYMBOL_AWARE 코드 적용(5번)** — config는 이미 SYMBOL_AWARE이나, 마지막 재색인이 docs만이라
   **코드 레이어 미적용**. 전체 `/reindex`(3시간) 해야 적용. 평가셋 깔고 A/B.
3. **[설정] 검색 파라미터 튜닝(6번)** — topK/pageBoost/overlap. 평가셋 기반, 감으로 바꾸지 말 것.
4. **[보류/드롭 권장] co-located 문서 제외(2번)** — `frontend/src/page/**/*.md`(signal-page.md, signal-components.md,
   trade-page.md, cesium-page.md, binance-page.md). **재검토 결과 위키와 중복 아님**(위키=요약, co-located=상세
   레퍼런스). 스팟체크("게이지 색 구간")에서 `signal-components.md`가 핵심 근거였음 → 제외 시 상세질문 답변력 손실.
   게다가 제외하려면 code 레이어 재색인(3시간 또는 도메인분할) 필요. **하지 않기를 권장.**
5. **[인프라/별도] 모델·속도** — 답변 LLM 생성이 ~27초(로컬 gemma, LATENCY 임계 초과). 챗모델 Claude 교체로 개선
   가능(재색인 불필요). 임베딩 클라우드화는 속도↑이나 3시간 재임베딩+과금+코드 외부전송(별도 결정).

## 제약·주의
- 2·5·6번 코드/설정 변경 → `CLAUDE.md`대로 수정 전 `gitnexus_impact`로 영향 분석 후 사용자에게 blast radius 보고 +
  `승인` 받고 진행. `EvidenceRetriever`/`PageContextRegistry`는 챗봇 전 경로 영향이라 특히 주의.
- 문서(docs/generated) 변경은 위험 낮음. 단 변경 후 `/reindex/docs` 1회로 챗봇 반영(사용자가 버튼 실행).
- 색인-시점 변경 여러 개는 모았다 마지막에 재색인 1회로 묶는 게 효율적. 단 묶으면 개별 기여 측정 불가 → 평가셋 있을 때만.

## 첫 메시지로 시작할 것
"위 인계 기준으로, 먼저 `docs/generated/log.md`와 `index.md`를 읽고 현황을 확인한 뒤, 평가셋(3번)부터 진행할지 —
아니면 챗모델 교체 같은 인프라 항목을 먼저 볼지 제안해줘."
