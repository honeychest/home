# chatbot

> 이 문서는 로컬 LLM(gemma-4-26b-a4b-it-mlx)이 소스 코드를 근거로 자동 생성했다. 검증 전 초안이다.

## 목차
- 개요 및 아키텍처 설계 원칙
- 설정 및 환경 구성 (Properties & Config)
- 코드베이스 데이터 수집 파이프라인 (Document Source)
- 비동기 재색인 프로세스 및 작업 관리 (Reindex Pipeline)
- 심볼 인지 기반 청킹 전략 (Symbol-Aware Chunking)
- RAG 기반 검색 및 근거 추출 (Evidence Retrieval)
- 답변 생성 및 프롬프트 제어 (Grounded Answer Generation)

## 개요 및 아키텍처 설계 원칙

본 시스템은 코드베이스를 대상으로 하는 RAG(Retrieval-Augmented Generation) 기반 챗봇을 구축하기 위해 설계되었습니다. 핵심 아키텍처는 데이터 수집, 청킹(Chunking), 벡터 저장 및 질의응답의 흐름을 따르며, 다음과 같은 설계 원칙을 준수합니다.

첫째, **비동기적이고 격리된 색인 프로세스**를 제공합니다. `CodebaseIndexingService`는 작업의 동시 실행을 방지하기 위해 `AtomicBoolean`을 통한 락 메커니즘을 사용하며, 실제 무거운 색인 작업은 `AsyncReindexRunner`를 통해 비동기로 실행됩니다. 이는 `@Async` 프록시의 특성을 고려하여 서비스 계층과 실행부 클래스를 분리함으로써 비동기 동작을 보장합니다.

둘째, **유연한 청킹 전략과 폴백(Fallback) 메커니즘**을 적용합니다. `CodebaseDocumentChunker`는 설정된 전략에 따라 토큰 기반 분할 또는 심볼 인지(`SYMBOL_AWARE`) 분할을 수행합니다. 특히 `SymbolAwareChunker`는 GitNexus의 심볼 경계를 활용하여 코드 구조를 반영한 정교한 청킹을 시도하되, 심볼 경계가 없거나 유효하지 않은 경우(stale) 즉시 기존 토큰 분할 방식으로 전환하여 색인 프로세스가 중단되지 않도록 설계되었습니다.

셋째, **데이터 정합성 및 외부 의존성 격리**를 중시합니다. `GitNexusBoundaryProvider`는 외부 CLI 도구에 의존하므로, 모든 실패 상황(타임아웃, 파싱 오류 등)을 흡수하여 빈 맵을 반환함으로써 시스템 전체의 안정성을 유지합니다. 또한, `PgVectorConfig`는 기존 MySQL 데이터소스와의 충돌을 방지하기 위해 전용 `DataSource`와 `JdbcTemplate`을 별도로 구성하여 벡터 저장소의 독립성을 확보합니다.

넷째, **근거 중심의 답변 생성**을 지향합니다. `ChatbotConfig`는 시스템 프롬프트를 통해 모델이 제공된 컨텍스 내에서만 답변하도록 강제하며, `GroundedAnswerGenerator`와 `EvidenceRetriever`는 검색된 문서의 메타데이터를 활용하여 답변과 함께 구적적인 근거 파일 경로를 사용자에게 제공할 수 있는 구조를 갖추고 있습니다.

## 설정 및 환경 구성 (Properties & Config)

### 1. 챗봇 동작 및 청킹 전략 설정
`ChatbotProperties` 클래스는 `chs.chatbot` 접두사를 사용하여 챗봇 운영에 필요한 핵심 설정을 관리합니다.
* **색인 대상 및 경로**: `indexRoots`를 통해 색인할 루트 디렉토리 목록을 지정하며, `sourceBase`를 설정하여 메타데이터의 경로를 상대화할 수 있습니다. (`ChatbotProperties.java`)
* **검색 파라미터**: 검색 시 참조할 상위 K개의 문서 수를 결정하는 `topK` 값을 제공합니다. (`ChatbotProperties.java`)
* **청킹 전략**: `Reindex` 내부 클래스를 통해 청킹 세부 설정을 관리합니다.
    * `includeExtensions`: 색인 대상 파일 확장자를 지정하며, 기본적으로 `.java`, `.html`, `.md`, `.tsx`, `.jsx`, `.ts`, `.js`를 포함합니다. (`ChatbotProperties.java`)
    * `chunkSize`, `minChunkSizeChars`, `minChunkLengthToEmbed`, `maxNumChunks`: 청크 크기 및 제약 조건을 설정합니다. (`ChatbotProperties.java`)
    * `chunkStrategy`: `TOKEN`(기본 토큰 분할) 또는 `SYMBOL_AWARE`(심볼 경계 기반 분할) 전략 중 하나를 선택합니다. (`ChatbotProperties.java`)
    * `overlapTokens`: 인접 청크 간의 경계 손실 방지를 위한 오버랩 토큰 수를 설정합니다. (`ChatbotProperties.java`)
    * `batchSize`: 벡터 저장소 기록 시 사용할 배치 크기를 지정합니다. (`ChatbotProperties.java`)
    * `gitnexusRepo`: 심볼 경계 조회를 위한 GitNexus 저장소명을 지정합니다. (`ChatbotProperties.java`)

### 2. 데이터베이스 및 벡터 저장소 설정
`PgVectorConfig`와 `PgVectorProperties`를 통해 PostgreSQL의 벡터 확장 기능을 사용하기 위한 전용 환경을 구성합니다.
* **데이터소스 분리**: MySQL `@Primary` 데이터소스와 충돌을 방지하기 위해 `pgVectorDataSource` 및 `pgVectorJdbcTemplate`을 별도로 생성하여 관리합니다. (`PgVectorConfig.java`)
* **벡터 저장소 구성**: `vectorStore` 빈은 `PgVectorProperties`에 정의된 접속 정보와 차원(dimensions)을 사용하여 생성됩니다. `initializeSchema(true)` 설정을 통해 최초 기동 시 테이블을 자동 생성하며, 인덱스 타입은 `NONE`으로 설정되어 전수 스캔 방식으로 동작합니다. (`PgVectorConfig.java`, `PgVectorProperties.java`)

### 3. 외부 통신 및 클라이언트 설정
* **HTTP 버전 고정**: `HttpClientConfig`는 외부 API(예: LM Studio) 호출 시 발생할 수 있는 무한 대기(hang) 현상을 방지하기 위해 `RestClient`의 요청 팩토리를 HTTP/1.1 버전으로 고정합니다. (`HttpClientConfig.java`)
* **시스템 프롬프트**: `ChatbotConfig`는 `ChatClient.Builder`를 통해 "제공된 근거에 있는 내용만 사용"하도록 강제하는 시스템 프롬프트를 설정하여 환각을 억제합니다. (`ChatbotConfig.java`)

## 코드베이스 데이터 수집 파이프라인 (Document Source)

`CodebaseDocumentSource` 클래스는 지정된 색인 루트 디렉토리들을 탐색하며 소스 파일을 수집하여 Spring AI `Document` 객체로 변환하는 역할을 수행한다.

수집 과정은 다음과 같은 단계로 진행된다:

1. **루트 디렉토리 탐색**: `ChatbotProperties`에 설정된 `indexRoots` 목록을 순회하며 각 경로를 탐색한다. 이때 `Files.walk`를 사용하여 디렉토리 트리를 재귀적으로 확인한다. (`springboot/src/main/java/com/chs/springboot/domain/chatbot/service/CodebaseDocumentSource.java`)
2. **파일 필터링**: `isIncluded` 메서드를 통해 파일 확장자를 검사한다. `ChatbotProperties`의 `reindex.includeExtensions`에 정의된 확장자를 포함하는 정규 파일만 수집 대상이 된다. (`springboot/src/main/java/com/chs/springboot/domain/chatbot/service/CodebaseDocumentSource.java`)
3. **문서 읽기 및 메타데이터 생성**: `readDocument` 메서드에서 파일 내용을 문자열로 읽어 들인다. 이때 각 `Document`의 메타데이터에는 `source` 키로 파일 경로가 저장된다.
    * 만약 `ChatbotProperties`에 `sourceBase`가 설정되어 있다면 해당 경로를 기준으로 상대화된 경로를 저장하며, 설정되지 않은 경우 각 루트 디렉토리를 기준으로 상대 경로를 생성한다. (`springboot/src/main/java/com/chs/springboot/domain/chatbot/service/CodebaseDocumentSource.java`)
    * 내용이 비어 있는 파일은 수집 대상에서 제외된다. (`springboot/src/main/java/com/chs/springboot/domain/chatbot/service/CodebaseDocumentSource.java`)

## 비동기 재색인 프로세스 및 작업 관리 (Reindex Pipeline)

비동기 재색인 프로세스는 `CodebaseIndexingService`에서 시작되며, 중복 실행을 방지하기 위해 `AtomicBoolean`을 이용한 락 메커니즘을 통해 단일 작업만 허용합니다. `startReindex` 메서드가 호출되면 새로운 `ReindexJob` 객체가 생성되어 `ConcurrentHashMap`에 저장되고, 비동기 실행을 위해 프록시를 경유하는 `AsyncReindexRunner.run` 메서드가 호출됩니다.

전체 파이프라인은 다음과 같은 단계로 진행됩니다:
1. **초기화**: `VectorIndexWriter.clear`를 호출하여 기존의 벡터 데이터를 삭제합니다.
2. **문서 수집**: `CodebaseDocumentSource.collect`를 통해 설정된 루트 경로에서 파일을 읽어 `Document` 객체 리스트로 수집합니다.
3. **청킹(Chunking)**: `CodebaseDocumentChunker.chunk`가 호출됩니다. 설정된 전략에 따라 `TokenTextSplitter`를 사용하거나, `SYMBOL_AWARE` 전략인 경우 `SymbolAwareChunker.chunk`를 통해 심볼 경계 기반의 정교한 분할을 수행합니다.
4. **색인 저장**: 생성된 청크들은 `VectorIndexWriter.write`를 통해 배치 단위로 `VectorStore`에 저장됩니다.

작업 상태 관리는 `ReindexJob` 객체를 통해 이루어집니다. 작업 중에는 `RUNNING` 상태를 유지하며, 성공 시 `markCompleted`를 통해 완료 상태로 전환되고 실패 시 `markFailed`를 통해 오류 메시지가 기록됩니다. 작업의 진행 상황(처리된 청크 수, 총 청크 수 등)은 `ReindexJob`에 실시간으로 업데이트되며, 이는 `ChatbotAdminController.getReindexStatus`를 통해 외부로 제공됩니다. 모든 작업이 완료되거나 실패하면 `AsyncReindexRunner`에서 전달된 콜백을 통해 `CodebaseIndexingService`의 실행 락이 해제됩니다.

## 심볼 인지 기반 청킹 전략 (Symbol-Aware Chunking)

심볼 인지 기반 청킹 전략은 GitNexus의 심볼 경계 정보를 활용하여 코드를 메서드나 클래스 단위로 정교하게 분할하는 방식이다. `ChatbotProperties.ChunkStrategy.SYMBOL_AWARE` 설정 시 활성화되며, `CodebaseDocumentChunker`를 통해 실행된다.

이 전략의 핵심은 '리프 우선(Leaf-first)' 방식과 'Gap-fill' 방식의 결합이다. `SymbolAwareChunker`는 GitNexus로부터 가져온 심볼 경계 중 가장 안쪽(작은) 심볼을 우선적으로 청크 단위로 채택한다. 이는 메서드나 함수가 클래스보다 먼저 라인을 점유하도록 하여, 내용 중복을 방지하면서도 의미 있는 단위로 분할하기 위함이다. 심볼에 포함되지 않은 나머지 줄들은 `gap-fill` 과정을 통해 연속된 구간으로 묶여 별도의 세그먼트로 생성된다.

생성된 세그먼트는 다음과 같은 규칙에 따라 최종 청크로 변환된다.
* **병합 및 분할**: 세그먼트의 텍스트 길이가 설정된 `chunkSize`를 초과하면 토큰 분할을 수행한다. 반대로 크기가 너무 작은 세그먼트들은 인접한 세그먼트와 병합되어 `minChunkSizeChars`를 충족하도록 구성된다.
* **오버랩(Overlap)**: 인접한 청크 간의 경계 손실을 방지하기 위해, 이전 청크의 꼬리 부분을 줄 단위로 잘라 다음 청크의 앞부분에 붙이는 방식을 사용한다.
* **안전장치(Fallback)**: GitNexus의 경계 정보가 실제 파일의 줄 수를 초과하는 등 데이터 불일치가 의심되거나, `source` 메타데이터가 없는 경우에는 자동으로 기존의 토큰 기반 분할(`tokenSplit`) 방식으로 폴백하여 색인 프로세스의 안정성을 보장한다.

최종적으로 생성된 각 청크는 텍스트와 함께 해당 구간의 라인 범위(`lines`) 및 심볼명(`symbol`) 정보를 메타데이터로 포함하여 `Document` 객체로 변환된다.

## RAG 기반 검색 및 근거 추출 (Evidence Retrieval)

질문과 관련된 코드베이스의 근거를 찾는 과정은 `EvidenceRetriever` 클래스의 `retrieve` 메서드를 통해 수행됩니다. 이 과정은 다음과 같은 단계로 진행됩니다.

먼저, `vectorStore`를 사용하여 사용자의 질문에 대해 유사도 검색을 수행합니다. 이때 검색 범위는 `ChatbotProperties`에 설정된 `topK` 값을 기준으로 결정됩니다. (`springboot/src/main/java/com/chs/springboot/domain/chatbot/service/EvidenceRetriever.java`)

검색 결과로 반환된 `Document` 객체들의 메타데이터에서 `source` 값을 추출합니다. 이 값은 파일 경로를 나타내며, 중복을 제거하여 고유한 소스 목록을 생성합니다. 최종적으로 `RetrievedEvidence` 객체는 검색된 문서 리스트(`documents`)와 추출된 소스 경로 목록(`sources`)을 포함하여 반환됩니다. (`springboot/src/main/java/com/chs/springboot/domain/chatbot/service/EvidenceRetriever.java`, `springboot/src/main/java/com/chs/springboot/domain/chatbot/service/RetrievedEvidence.java`)

이렇게 추출된 근거는 이후 `ChatbotService`에서 답변 생성 시 활용되며, 사용자에게는 답변과 함께 근거가 된 파일 경로 목록이 전달됩니다. (`springboot/개요 및 아키텍처 설계 원칙` 섹션에 명시된 바와 같이 `ChatbotService`는 `EvidenceRetriever`로부터 얻은 정보를 활용하여 `ChatResponse`를 생성합니다.)

## 답변 생성 및 프롬프트 제어 (Grounded Answer Generation)

`ChatbotService`의 `ask` 메서드는 질문을 수신하면 `EvidenceRetriever`를 통해 관련 근거를 검색하고, `GroundedAnswerGenerator`를 호출하여 답변을 생성합니다. (`springboot/src/main/java/com/chs/springboot/domain/chatbot/service/ChatbotService.java`)

`GroundedAnswerGenerator`는 `QuestionAnswerAdvisor`를 사용하여 검색된 컨텍스트를 프롬프트에 포함하는 RAG(Retrieval-Augmented Generation) 방식을 사용합니다. `QuestionAnswerAdvisor`는 `vectorStore`를 기반으로 질문과 관련된 문서를 검색하도록 설정됩니다. (`springboot/src/main/java/com/chs/springboot/domain/chatbot/service/GroundedAnswerGenerator.java`)

프롬프트 제어는 `ChatbotConfig`에 정의된 `chatbotChatClient` 빈을 통해 이루어집니다. 이 클라이언트는 시스템 프롬프트로 "반드시 제공된 근거(컨텍스)에 있는 내용만 사용해 한국어로 답하라", "근거에 없는 내용은 추측하지 말고 '모름' 이라고 답하라", "가능하면 근거가 된 파일 경로를 함께 제시하라"는 규칙을 강제하여 환각(Hallucination)을 억제합니다. (`springboot/src/main/java/com/chs/springboot/domain/chatbot/config/ChatbotConfig.java`)

최종적으로 생성된 답변은 `ChatResponse` 객체에 담겨 반환되며, 이때 검색을 통해 확보된 근거 파일 목록(`sources`)이 함께 포함됩니다. (`springboot/src/main/java/com/chs/springboot/domain/chatbot/dto/ChatResponse.java`)
