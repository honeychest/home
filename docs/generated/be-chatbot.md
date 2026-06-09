# Chatbot (코드베이스 RAG)

> 이 문서는 로컬 LLM(gemma-4-26b-a4b-it-mlx)이 소스 코드를 근거로 자동 생성했다. 검증 전 초안이다.

## 목차
- 개요 및 시스템 아키텍처
- 설정 및 환경 구성 (Properties & Config)
- 코드베이스 색인 파이프라인 (Reindexing Pipeline)
- 심볼 인지 청킹 전략 (Symbol-Aware Chunking)
- 검색 및 근거 수집 (Evidence Retrieval)
- RAG 기반 답변 생성 (Grounded Answer Generation)
- 관리 및 모니터링 API (Admin & Status Control)

## 개요 및 시스템 아키텍처

본 시스템은 코드베이스를 대상으로 하는 RAG(Retrieval-Augmented Generation) 기반의 챗봇 서비스입니다. 시스템은 크게 코드베이스 데이터 수집 및 색인(Indexing) 프로세스와 사용자 질의에 대한 답변 생성(Generation) 프로세스로 구성됩니다.

**1. 데이터 수집 및 색인 프로세스 (Indexing Pipeline)**
코드베이스의 소스 코드를 벡터 저장소에 저장하기 위한 비동기식 풀 리빌드(Full Rebuild) 과정을 거칩니다.
*   **작업 오케스트레이션**: `CodebaseIndexingService`가 색인 작업의 시작을 제어하며, 중복 실행 방지를 위한 락(Lock)과 작업 상태 관리를 수행합니다. 실제 비동기 실행은 `AsyncReindexRunner`를 통해 프록시를 경유하여 수행됩니다.
*   **데이터 수집**: `CodebaseDocumentSource`가 설정된 루트 경로(`ChatbotProperties.indexRoots`)를 탐색하며, 지정된 확장자를 가진 파일을 읽어 `Document` 객체로 생성합니다.
*   **청킹(Chunking) 전략**: `CodebaseDocumentChunker`는 설정된 전략에 따라 두 가지 방식으로 동작합니다.
    *   **TOKEN**: `TokenTextSplitter`를 사용하여 토큰 단위로 분할합니다.
    립 **SYMBOL_AWARE**: `SymbolAwareChunker`가 `GitNexusBoundaryProvider`로부터 가져온 심볼 경계 정보를 활용하여 메서드나 클래스 단위로 정교하게 분할합니다. 만약 심볼 경계 정보가 없거나 유효하지 않은 경우 토큰 분할 방식으로 폴백(Fallback)합니다.
*   **벡터 저장**: `VectorIndexWriter`는 기존 데이터를 삭제(`clear`)한 후, 생성된 청크들을 배치(Batch) 단위로 `VectorStore`에 저장합니다.

**2. 질의응답 프로세스 (RAG Pipeline)**
사용자의 질문에 대해 관련 코드를 검색하고 답변을 생성하는 과정입니다.
*   **질의 수신**: `ChatbotController`가 사용자의 질문을 수신하여 `ChatbotService`로 전달합니다.
*   **근거 검색(Retrieval)**: `EvidenceRetriever`가 `VectorStore`를 대상으로 유사도 검색(`similaritySearch`)을 수행하여 질문과 관련된 코드 청크들을 추출합니다.
*   **답변 생성(Generation)**: `GroundedAnswerGenerator`는 검색된 근거를 바탕으로 `ChatClient`를 사용하여 답변을 생성합니다. 이때 `ChatbotConfig`에 정의된 시스템 프롬프트가 적용되어, 반드시 제공된 근거 내에서만 답변하도록 제어됩니다.
*   **결과 반환**: 생성된 답변과 검색된 근거 파일 목록(`sources`)이 `ChatResponse`에 담겨 사용자에게 반환됩니다.

## 설정 및 환경 구성 (Properties & Config)

### 1. 챗봇 동작 및 청킹 전략 설정
`ChatbotProperties`(`springboot/src/main/java/com/chs/springboot/domain/chatbot/config/ChatbotProperties.java`)를 통해 챗봇 운영에 필요한 핵심 파라미터를 관리한다.
*   **색인 대상 및 경로**: `indexRoots`를 통해 색인할 루트 디렉토리 목록을 지정하며, `sourceBase` 설정 시 메타데이터의 경로를 해당 기준에 맞춰 상대화한다.
*   **청킹 전략 (`Reindex`)**: 
    *   `chunkStrategy`: `TOKEN`(기존 토큰 기반 분할)과 `SYMBOL_AWARE`(GitNexus 심볼 경계 활용) 중 하나를 선택할 수 있다.
    *   `chunkSize`, `minChunkSizeChars`, `minChunkLengthToEmbed`, `maxNumChunks`: 청킹 시 사용되는 크기 및 제약 조건이다.
    *   `includeExtensions`: 색인 대상 파일 확장자 목록을 관리한다. (기본값: `.java`, `.html`, `.md`, `.tsx`, `.jsx`, `.ts`, `.js`)
    *   `overlapTokens`: 인접 청크 간 경계 손실 방지를 위한 오버랩 토큰 수이다.
    *   `batchSize`: 벡터 저장소 기록 시 사용할 배치 크기이다.
    *   `gitnexusRepo`: 심볼 경계 조회를 위한 GitNexus 저장소명이다.

### 2. 벡터 데이터베이스(PGVector) 설정
`PgVectorConfig`(`springboot/src/main/java/com/chs/springboot/domain/chatbot/config/PgVectorConfig.java`)와 `PgVectorProperties`(`springboot/sringboot/src/main/java/com/chs/springboot/domain/chatbot/config/PgVectorProperties.java`)를 통해 PostgreSQL의 벡터 확장 기능을 위한 설정을 수행한다.
*   **데이터소스 분리**: MySQL `@Primary` 데이터소스와 충돌을 방지하기 위해 `pgVectorDataSource` 및 `pgVectorJdbcTemplate`을 별도로 생성하여 관리한다.
*   **벡터 저장소 구성**: `vectorStore` 빈은 `PgVectorStore`를 사용하여 생성된다.
    *   `dimensions`: 임베딩 모델의 출력 차원(예: 2560)과 일치하도록 설정한다.
    *   `distanceType`: 코사인 유사도(`COSINE_DISTANCE`)를 사용한다.
    *   `indexType`: 데이터 규모를 고려하여 인덱스 없이 정확 검색(`NONE`)을 수행하도록 설정되어 있다.
    *   `initializeSchema(true)`: 최초 기동 시 `vector_store` 테이블을 자동 생성한다.

### 3. 외부 통신 및 클라이언트 설정
*   **HTTP/1.1 고정**: `HttpClientConfig`(`springboot/src/main/java/com/chs/springboot/domain/chatbot/config/HttpClientConfig.java`)는 `RestClientCustomizer`를 통해 외부 호출 시 HTTP 버전을 1.1로 고정한다. 이는 LM Studio와 같은 서버가 평문 HTTP/2를 처리하지 못해 발생하는 무한 대기(hang) 현상을 방지하기 위함이다.
*   **챗봇 클라이언트**: `ChatbotConfig`(`springboot/src/main/java/com/chs/springboot/domain/chatbot/config/ChatbotConfig.java`)는 `ChatClient` 빈을 생성하며, 시스템 프롬프트를 통해 "제공된 근거에 있는 내용만 사용"하도록 강제하여 환각을 억제한다.

## 코드베이스 색인 파이프라인 (Reindexing Pipeline)

코드베이스 색인 파이프라인은 `CodebaseIndexingService`를 통해 오케스트레이션되며, `AsyncReindexRunner.run()` 메서드를 통해 비동기적으로 실행됩니다. 전체 프로세스는 다음과 같은 단계로 구성됩니다.

1.  **데이터 수집 (Collection)**: `CodebaseDocumentSource.collect()`가 실행되어 설정된 색인 루트(`ChatbotProperties.indexRoots`)를 탐색합니다. 지정된 확장자(`ChatbotProperties.Reindex.includeExtensions`)에 해당하는 파일을 읽어 `Document` 객체로 생성하며, 파일 경로는 설정된 `sourceBase`를 기준으로 상대화되어 메타데이터에 저장됩니다.
2.  **청킹 (Chunking)**: 수집된 문서들은 `CodebaseDocumentChunker.chunk()`를 통해 분할됩니다. 
    *   `SYMBOL_AWARE` 전략이 활성화된 경우, `SymbolAwareChunker.chunk()`가 호출됩니다. 이 과정에서 `GitNexusBoundaryProvider.loadBoundaries()`를 통해 GitNexus로부터 심볼 경계 정보를 가져옵니다. 
    *   `SymbolAwareChunker`는 '리프 우선(Leaf-first)' 방식을 사용하여 가장 작은 심볼 단위를 우선적으로 청크로 채택하고, 남은 영역을 'gap-fill' 방식으로 처리합니다. 너무 큰 세그먼트는 `TokenTextSplitter`를 통해 분할되며, 인접 청크 간에는 설정된 오버랩(`overlapTokens`)이 적용됩니다.
    *   심볼 경계가 없거나 유효하지 않은 경우(예: `stale`한 경계), 해당 파일은 자동으로 기존의 토큰 기반 분할 방식으로 폴백(Fallback)됩니다.
3.  **색인 쓰기 (Writing)**: 생성된 청크들은 `VectorIndexWriter.write()`를 통해 저장소에 기록됩니다. 
    *   작업 시작 시 `VectorIndexWriter.clear()`가 호출되어 기존의 `vector_store` 테이블 데이터를 삭제합니다.
    *   청크들은 설정된 배치 크기(`ChatbotProperties.Reindex.batchSize`) 단위로 `VectorStore.add()`를 통해 저장됩니다. 
    *   진행 상태는 `ReindexJob` 객체에 실시간으로 업데이트되어 작업의 진행률(`processedChunks`)과 완료 상태를 관리합니다.

## 심볼 인지 청킹 전략 (Symbol-Aware Chunking)

심볼 인지 청킹 전략은 `ChatbotProperties.ChunkStrategy.SYMBOL_AWARE` 설정 시 동작하며, GitNexus의 심볼 경계 정보를 활용하여 코드를 메서드나 클래스 단위로 정밀하게 분할하는 방식이다. `GitNexusBoundaryProvider`가 `npx gitnexus cypher` 명령을 통해 추출한 파일별 심볼 경계(시작 줄, 끝 줄, 심볼명)를 기반으로 수행된다.

핵심 알고리즘은 '리프 우선(Leaf-first)' 및 'Gap-fill' 전략을 따른다. `SymbolAwareChunker.buildSegments` 메서드는 가장 작은(안쪽) 심볼을 우선적으로 점유하여 청크 단위로 채택한다. 이를 통해 클래스보다 하위 개념인 메서드/함수가 먼저 라인을 점유하게 하며, 심볼이 겹치는 상위 컨테이너는 스킵하여 내용 중복을 방지한다. 심볼에 포함되지 않은 나머지 줄들은 `gap-fill` 과정을 통해 연속된 구간으로 묶인다.

생성된 세그먼트는 `mergeAndSplit` 과정을 거친다. 설정된 `chunkSize`를 초과하는 큰 세그먼트는 `TokenTextSplitter`를 통해 다시 쪼개지며, 작은 세그먼트들은 `minChunkSizeChars`를 충족할 때까지 인접한 세그먼트들과 병합된다. 또한 `applyOverlap` 메서드를 통해 인접 청크 간에 이전 청크의 꼬리 부분을 줄 단위로 붙여 경계에서의 정보 손실을 방지한다.

안전장치로서 `SymbolAwareChunker`는 다음과 같은 경우 토큰 분할(Token-based splitting)로 폴백한다.
* `source` 메타데이터가 존재하지 않는 경우
* 파일 내에 유효한 심볼 경계가 없는 경우
* 추출된 경계의 끝줄이 실제 파일의 전체 줄 수를 초과하는 경우(`stale` 의심)

최종적으로 생성된 청크는 `source` 경로, 줄 범위(`lines`), 그리고 추출된 심볼명(`symbol`)을 메타데이터로 포함하는 `Document` 객체로 변환된다.

## 검색 및 근거 수집 (Evidence Retrieval)

`EvidenceRetriever` 클래스는 `VectorStore`를 사용하여 질문과 관련된 코드베이스 근거를 검색하는 역할을 수행한다. `retrieve(String question)` 메서드는 `ChatbotProperties`에 설정된 `topK` 값을 기반으로 `SearchRequest`를 생성하여 `vectorStore.similaritySearch`를 호출한다.

검색 결과로 반환된 `Document` 리스트에서 각 문서의 메타데이터에 포함된 `source` 값을 추출한다. 이 과정에서 `null`이거나 공백인 값은 제외하며, `distinct()`를 통해 중복된 파일 경로를 제거한다. 최종적으로 검색된 문서 목록과 정제된 소스 경로 리스트를 `RetrievedEvidence` 객체에 담아 반환한다.

이 과정에서 사용되는 데이터 흐름은 다음과 같다:
*   `ChatbotService.ask(String question)` 메서드가 `evidenceRetriever.retrieve(question)`를 호출하여 근거를 확보한다. (`springboot/src/main/java/com/chs/springboot/domain/chatbot/service/ChatbotService.java`)
*   `EvidenceRetriever.retrieve(String question)`는 `vectorStore`를 통해 유사도 검색을 수행하고, 메타데이터의 `source` 정보를 추출하여 `RetrievedEvidence`를 생성한다. (`springboot/src/main/java/com/chs/springboot/domain/chatbot/service/EvidenceRetriever.java`)
*   `RetrievedEvidence`는 검색된 `Document` 목록과 추출된 `sources` 문자열 리스트를 보유한다. (`springboot/src/main/java/com/chs/springboot/domain/chatbot/service/RetrievedEvidence.java`)

## RAG 기반 답변 생성 (Grounded Answer Generation)

RAG 기반 답변 생성은 `ChatbotService`가 질문을 수신하면 `EvidenceRetriever`를 통해 관련 근거를 검색하고, `GroundedAnswerGenerator`가 이를 바탕으로 답변을 생성하는 흐름으로 진행됩니다.

먼저 `EvidenceRetriever`는 `VectorStore`를 사용하여 질문과 유사한 문서를 검색합니다. 이때 `ChatbotProperties`에 설정된 `topK` 값을 기준으로 검색을 수행하며, 검색된 각 `Document`의 메타데이터에서 `source` 정보를 추출하여 질문과 관련된 파일 경로 목록을 포함한 `RetrievedEvidence` 객체를 생성합니다 (`springboot/src/main/java/com/chs/springboot/domain/chatbot/service/EvidenceRetriever.java`).

검색된 근거를 바탕으로 답변을 생성하는 단계는 `GroundedAnswerGenerator`에서 담당합니다. 이 클래스는 `QuestionAnswerAdvisor`를 사용하여 `VectorStore`에 저장된 정보를 검색할 수 있는 어드바이저를 구성합니다. 이후 `ChatClient`의 프롬프트에 해당 어드바이저를 등록하여 질문을 전달함으로써, 검색된 컨텍스트 내에서만 답변이 생성되도록 합니다 (`springboot/src/main/java/com/chs/springboot/domain/chatbot/service/GroundedAnswerGenerator.java`).

최종적으로 `ChatbotService`는 생성된 답변과 `EvidenceRetriever`로부터 얻은 근거 파일 목록을 결합하여 `ChatResponse` 객체를 반환합니다 (`springboot/src/main/java/com/chs/springboot/domain/chatbot/service/ChatbotService.java`). 이때 `ChatbotConfig`에 정의된 시스템 프롬프트는 모델이 반드시 제공된 근거 내에서만 답변하고, 근거가 없는 경우 "모름"이라고 답하도록 강제하여 환각을 억제합니다 (`springboot/src/main/java/com/chs/springboot/domain/chatbot/config/ChatbotConfig.java`).

## 관리 및 모니터링 API (Admin & Status Control)

`ChatbotAdminController.java`를 통해 제공되는 관리 및 모니터링 API는 다음과 같습니다.

*   **재색인 작업 시작 (`POST /api/admin/chatbot/reindex`)**: `CodebaseIndexingService.java`를 호출하여 새로운 색인 작업을 시작합니다. 작업이 이미 실행 중인 경우 `IllegalStateException`을 발생시키며, 성공 시 `ReindexJob.java`에 정의된 작업 ID와 상태를 반환합니다.
*   **재색인 상태 조회 (`GET /api/admin/chatbot/reindex/{id}`)**: 특정 작업의 진행 상태를 확인합니다. `ReindexJob.java`에 기록된 작업 ID, 상태(RUNNING, COMPLETED, FAILED), 문서 수, 처리된 청크 수, 전체 청크 수, 오류 메시지 등을 `ReindexStatusResponse.java` 형식으로 반환합니다. 만약 존재하지 않는 ID인 경우 `404 Not Found`를 반환합니다.
