# 핸드오프: 생성된 docs 를 RAG 챗봇에 '적용'하기

> 새 세션에서 이 작업을 이어서 진행한다. 이 문서 하나만 읽어도 맥락이 서도록 정리했다.

## 0. 한 줄 요약
로컬 LLM 으로 만든 도메인 문서(`docs/generated/*.md`)를 챗봇의 벡터 인덱스에 **2중 레이어(자연어 층)** 로
편입해, 개념질문("이 기능 뭐야") 품질을 올리는 게 이번 작업의 목표다. **아직 적용은 안 됐다.**

## 1. 지금까지 된 것 (배경)
- **청킹 개선(완료)**: 토큰 맹목분할 → GitNexus 심볼 경계 기반 `SYMBOL_AWARE` 청킹 도입.
  - 핵심 파일: `springboot/.../domain/chatbot/service/SymbolAwareChunker.java`,
    `GitNexusBoundaryProvider.java`, 분기 진입점 `CodebaseDocumentChunker.java`.
  - 설정: `application.properties` 의 `chs.chatbot.reindex.chunk-strategy=SYMBOL_AWARE`,
    `chunk-size=512`, `overlap-tokens=64`, `gitnexus-repo=lab`.
  - 단위테스트 6건 통과(경계有/경계無/stale/source無/전략분기).
- **문서 생성 파이프라인(완료·검증완료)**: `docs/scripts/gen-domain-doc.ps1`.
  - 로컬 LLM(LM Studio gemma) 3패스(목차→섹션→자가검증)로 도메인 문서 자동 생성. **Claude 미개입.**
  - 일괄 실행은 `docs/scripts/RUNBOOK.md` 참고(백엔드 5 + 프론트 도메인/페이지/교차영역).
  - chatbot 도메인으로 시범 생성 후 Claude 가 1회 검증 → 강화 프롬프트 적용 후 재검증 통과.

## 2. 이번 세션 목표 (해야 할 일)
생성된 `docs/generated/*.md` 를 챗봇 검색이 끌어쓰게 **인덱스에 편입**한다.
이미 합의된 방식 = **"같은 인덱스 + layer 태그"** (2단계 계층검색은 후순위).

### 단계
1. **색인 대상에 docs 폴더 추가**
   - 파일: `springboot/src/main/resources/application-local.properties` (47번째 줄 `chs.chatbot.index-roots`)
   - `C:/Users/Tissue/IdeaProjects/home/docs/generated` 를 루트로 추가.
   - `.md` 는 이미 `includeExtensions` 에 있어 별도 확장자 설정 불필요.
   - `source-base` 가 레포 루트라, doc 의 `source` 메타데이터는 `docs/generated/xxx.md` 로 기록된다.
2. **(선택·권장) 레이어 태그 부여**
   - 파일: `springboot/.../domain/chatbot/service/CodebaseDocumentSource.java` 의 `readDocument`.
   - 경로가 `docs/generated/` 로 시작하면 메타데이터에 `layer="doc"`, 아니면 `layer="code"` 부여.
   - 목적: 나중에 검색에서 문서/코드를 구분하거나 가중치를 줄 수 있게 함(지금 당장은 태그만).
3. **재색인 후 검증**
   - `POST /api/admin/chatbot/reindex` → `GET /api/admin/chatbot/reindex/{id}` 로 상태 폴링.
   - 개념질문 몇 개로 챗봇에 물어보고, 응답 `sources` 에 `docs/generated/*.md` 가 뜨는지 확인.

## 3. 이미 내려진 결정 (다시 논의 불필요)
- 청킹 전략 = `SYMBOL_AWARE` (GitNexus 심볼 경계 재활용). 폴백 내장.
- 통합 방식 = **같은 pgvector 인덱스에 문서 편입**(2단계 계층검색은 효과 본 뒤 검토).
- 문서 생성은 **로컬 LLM 전용**, Claude 는 생성 로직에 넣지 않는다. 검증은 요청 시 일회성.

## 4. 주의/리스크
- **공유 인프라**: pgvector(`chs_vector`)와 LM Studio 는 Mac mini(Tailscale `100.69.229.3`)의 공유 자원.
  재색인은 `VectorIndexWriter.clear()` 로 인덱스를 **비우고 재구축**한다. 실서비스 영향 고려해 실행 시점 합의 필요.
- **앱 재기동**: 코드(2번)를 바꾸면 앱을 재빌드/재기동해야 반영된다. 1번(설정만)이면 재기동만으로 충분.
- **SYMBOL_AWARE 신선도**: 코드가 바뀐 뒤 재색인하려면 `npx gitnexus analyze` 를 먼저 돌려 경계를 최신화하는 게 좋다(안 하면 바뀐 파일은 무해하게 토큰 폴백).
- **문서 품질**: 생성물은 초안 등급. 적용 전 `docs/generated/` 를 한 번 검증받는 것을 권장.

## 5. 빠른 점검 명령
```powershell
# 앱 헬스(8080)
curl -s http://localhost:8080/actuator/health
# 재색인 시작
curl -s -X POST http://localhost:8080/api/admin/chatbot/reindex
# 상태 조회(반환된 jobId 사용)
curl -s http://localhost:8080/api/admin/chatbot/reindex/<jobId>
```

## 6. 관련 파일 지도
| 목적 | 경로 |
| --- | --- |
| 색인 루트 설정 | `springboot/src/main/resources/application-local.properties` |
| 청킹 전략/오버랩 설정 | `springboot/src/main/resources/application.properties` |
| 문서 수집(여기에 layer 태그 추가) | `.../chatbot/service/CodebaseDocumentSource.java` |
| 청킹 분기 | `.../chatbot/service/CodebaseDocumentChunker.java` |
| 심볼 청킹 | `.../chatbot/service/SymbolAwareChunker.java` |
| 재색인 실행 | `.../chatbot/service/AsyncReindexRunner.java` |
| 재색인 API | `.../chatbot/controller/ChatbotAdminController.java` |
| 문서 생성 파이프라인 | `docs/scripts/gen-domain-doc.ps1` |
| 일괄 생성 런북 | `docs/scripts/RUNBOOK.md` |
| 생성물 | `docs/generated/*.md` |
