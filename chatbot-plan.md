# Spring AI 코드베이스 RAG 챗봇 — 구현 계획서

## 0. 결정 현황
```
3-1 (벡터 저장소) : PGVector (PostgreSQL + pgvector)
3-2 (색인 대상)   : 자바(.java) + 화면(.html) + 문서(.md)
3-3 (색인 시점)   : 수동 (POST /api/admin/chatbot/reindex, 인증·비동기)
LLM 채팅          : gemma-4-26b-a4b-it-mlx  (LM Studio)
LLM 임베딩        : text-embedding-qwen3-embedding-4b (LM Studio, dim 2560 실측)
LM Studio 주소    : local  http://100.69.229.3:2345        (Tailscale IP)
                   prod   http://host.docker.internal:2345 (같은 호스트, Tailscale 불필요)
                   (Spring AI는 /v1 자동부착)
```

> 검증 보완 반영(2026-06-08):
> [A] PGVector를 오토컨피그에 위임하지 않고 PG 전용 DataSource/JdbcTemplate/
>     PgVectorStore 빈을 직접 등록 (기존 MySQL `@Primary` 충돌 회피).
> [B] qwen3-embedding-4b 실제 임베딩 차원을 측정 후 명시(2560 단정 금지).
> [C] reindex를 `/api/admin/**` 인증 경로로 이동 + 비동기/상태조회 방식.
> [D] 패키지 위치를 `com.chs.springboot.domain.chatbot` 로 (features 미사용).
>
> 검증 2차 보완 반영(2026-06-08) — 실측 기반:
> [E] PgVector auto-configuration을 `exclude`로 명시 제외(아래 [A]/[E] 참조).
>     실측: 이미 `DataSourceConfig`가 `@Primary primaryDataSource`+`batchDataSource`
>     +`batchJdbcTemplate`를 수동 등록 중 → PG는 3번째, 충돌 위험 확정.
> [F] reindex 정책 = 전체 삭제 후 재적재(full rebuild) + 동시실행 잠금(중복/잔존 방지).
> [G] 실측 확인: SecurityConfig.java:120 에서 `/api/admin/**`는 이미
>     `.hasAnyAuthority("ADMIN_ACCESS")`로 보호됨. → reindex를 `/api/admin/...`에
>     두면 인증·권한이 자동 적용(추가 보안설정 불필요). `/api/chat`는 permitAll.
> [H] jar 배포 시 색인 대상 경로 전략 명시(classpath 리소스 vs 외부 파일시스템).
> [I] Spring AI 패치버전 고정(빌드 환경: Spring Boot 3.4.2 / Java 17 → 1.0.x 라인).
> [J] PGVector(당초 계획): 서버는 docker-compose(`chs-network`)에 서비스 추가, 로컬은
>     Docker Desktop GUI로 별도 기동.
>     → 2026-06-09 변경: 운영에 공유 PG 1개만 두고 로컬은 Tailscale로 접속. 10.5 참조.
> [K] TokenTextSplitter의 Java 구조 손실은 MVP 비차단 → 검색품질 문제 시
>     코드 인지 분할로 교체(개선 과제).

## 1. 목표 / 성공 기준
- 사용자가 채팅 화면에서 한국어로 코드베이스를 질문하면, 관련 소스 조각을 근거로 답한다.
- 검증 기준:
  1. `POST /api/admin/chatbot/reindex`(인증) 호출 → 작업ID 반환 후 상태조회에서 색인 문서 수가 0보다 큼.
  2. "여기서 사용되는 레디스 키는 뭐야?" → 실제 redis 키 관련 코드 근거 포함 답변.
  3. "error-500 화면은 무슨 용도야?" → 템플릿 기반 답변.
  4. 근거가 없으면 "모름"이라고 답함(환각 억제).

## 2. 전체 아키텍처
```
[색인]  파일수집(.java/.html/.md) → 청킹 → qwen3 임베딩 → PGVector 저장
[질문]  질문 → qwen3 임베딩 → PGVector 유사검색(top-k)
        → [근거 + 질문]을 gemma에 전달 → 답변 → Thymeleaf 화면
```

## 3. 추가/변경 파일 목록
```
springboot/build.gradle                  [수정] Spring AI BOM + 의존성
springboot/src/main/resources/
  application.properties                 [수정] Spring AI 공통 설정
  application-local.properties           [수정] LM Studio/PG 로컬값
  templates/chatbot/chat.html            [신규] 채팅 화면
domain/chatbot/
  config/PgVectorConfig.java             [신규] PG 전용 DataSource/JdbcTemplate/
                                                PgVectorStore 빈 수동 등록
  config/ChatbotConfig.java              [신규] ChatClient 빈
  service/CodebaseIndexingService.java   [신규] 파일수집·청킹·색인
  service/ChatbotService.java            [신규] RAG 질의응답
  controller/ChatbotController.java      [신규] 화면 + API
  controller/ChatbotAdminController.java [신규] /api/admin 색인 + 상태조회
  dto/ChatRequest.java, ChatResponse.java[신규] 요청/응답 DTO
  dto/ReindexJob.java                    [신규] 비동기 색인 작업 상태
springboot/docker-compose.yml            [수정] 서버용 chs-pgvector 서비스 추가 [J]
  (로컬은 Docker Desktop GUI로 별도 기동 — compose 불필요)
```
> 패키지 베이스: `com.chs.springboot`. 실제 프로젝트 컨벤션은 `domain.*` / `global.*`이며
> `features` 패키지는 존재하지 않으므로, 챗봇은 `com.chs.springboot.domain.chatbot` 하위에 배치.

## 4. 의존성 (build.gradle)
```gradle
// dependencyManagement 에 Spring AI BOM 추가 — 패치버전 고정 [I]
// 빌드 환경: Spring Boot 3.4.2 / Java 17 (실측) → Spring AI 1.0.x 라인 호환
implementation platform("org.springframework.ai:spring-ai-bom:1.0.0")  // 구현 시 최신 1.0.x 패치로 고정
// OpenAI 호환(=LM Studio) 채팅+임베딩
implementation 'org.springframework.ai:spring-ai-starter-model-openai'
// PGVector 벡터 저장소
implementation 'org.springframework.ai:spring-ai-starter-vector-store-pgvector'
// PostgreSQL 드라이버 (PG 전용 DataSource용)
runtimeOnly 'org.postgresql:postgresql'
```
> [I] "1.0.x"로 두지 말고 단일 패치버전 문자열로 고정한다(재현성). 구현 시
>     Maven Central에서 3.4.2 호환 최신 1.0.x 확정 후 BOM 버전에 박는다.
> [E] PgVector starter는 auto-config가 `@Primary` JdbcTemplate(=MySQL)을 물 수 있으므로,
>     `@SpringBootApplication(exclude = {PgVectorStoreAutoConfiguration.class})`
>     (또는 properties `spring.autoconfigure.exclude`)로 **명시 제외**하고,
>     PgVectorStore 빈은 섹션 5의 PgVectorConfig에서 직접 등록한다.

## 5. 설정 (application.properties / -local / -prod)
```properties
# --- application.properties (공통) ---
spring.ai.openai.api-key=lm-studio
spring.ai.openai.chat.options.model=gemma-4-26b-a4b-it-mlx
spring.ai.openai.embedding.options.model=text-embedding-qwen3-embedding-4b
chs.pgvector.dimensions=2560          # 실측 완료 [B]
# PgVector 오토컨피그 제외 [E] (PG 전용 빈은 PgVectorConfig에서 직접 등록)
spring.autoconfigure.exclude=org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration

# --- application-local.properties (이 dev PC) --- [2026-06-09 현행화: 10.5]
spring.ai.openai.base-url=http://100.69.229.3:2345          # Tailscale IP (Mac mini LM Studio)
# PGVector: 로컬도 운영 Mac mini 의 공유 PG 에 Tailscale 로 접속(로컬 Docker 폐기)
chs.pgvector.url=jdbc:postgresql://100.69.229.3:5432/chs_vector
chs.pgvector.username=${PGVECTOR_USERNAME:postgres}
chs.pgvector.password=${PGVECTOR_PASSWORD:...}   # .env(jasypt 대상), 운영과 동일 값
# 색인 대상 = 백엔드+프론트 소스(멀티루트), source 경로는 레포 기준
chs.chatbot.index-roots=.../springboot/src,.../frontend/src
chs.chatbot.source-base=.../home

# --- application-prod.properties (Mac mini orbstack) ---
spring.ai.openai.base-url=http://host.docker.internal:2345  # 같은 호스트, Tailscale 불필요
chs.pgvector.url=jdbc:postgresql://chs-pgvector:5432/chs_vector  # compose 서비스명
```
> base-url은 로컬=Tailscale IP, 운영=host.docker.internal로 분리. 운영은 LM Studio가
> 앱 컨테이너와 같은 Mac mini에 있어 Tailscale·공개도메인(devcontext.net) 불필요.
> 단, prod에서 컨테이너→호스트가 닿으려면 LM Studio가 0.0.0.0 바인딩이어야 함(배포 시 확인).

### [A]/[E] 멀티 DataSource 충돌 회피 (핵심)
실측: `global/config/DataSourceConfig.java`가 이미 아래처럼 수동 등록 중.
```
  DataSourceConfig (기존)
    ├─ @Primary @Bean primaryDataSource  (spring.datasource.hikari)
    ├─ @Bean batchDataSource             (spring.datasource.batch.hikari)
    └─ @Bean batchJdbcTemplate
```
PG는 여기에 더해지는 3번째 DataSource다. PgVector 스타터의 auto-config는
`@Primary` JdbcTemplate(=MySQL)을 물 위험이 크므로, **auto-config를 제외([E])**
하고 동일 컨벤션으로 **PG 전용 빈을 직접 등록**한다.
```
  PgVectorConfig (@Configuration)  ← DataSourceConfig 패턴 그대로
    ├─ @Bean pgDataSource      (chs.pgvector.* 로 직접 구성, @Primary 아님)
    ├─ @Bean pgJdbcTemplate    (pgDataSource 주입, @Qualifier로 분리)
    └─ @Bean pgVectorStore     (pgJdbcTemplate + EmbeddingModel + dimensions)
```
> `spring.ai.vectorstore.pgvector.*` 오토컨피그 프로퍼티는 사용하지 않는다.
> auto-config 제외는 섹션 4 [E] 참조. 스키마 초기화도 이 설정 클래스 안에서 통제한다.

### [B] 임베딩 차원 — 실측 완료 (2560)
PGVector 테이블은 생성 시 vector 차원이 고정되므로 실제 출력 차원을 측정했다.
```
  측정: POST /v1/embeddings (model=text-embedding-qwen3-embedding-4b)
        → 응답 embedding 배열 length = 2560  → chs.pgvector.dimensions=2560 고정
  주의: 임베딩 모델을 교체하면 차원이 달라질 수 있음 → 교체 시 재측정 + 재색인(full rebuild).
  참고: 처음 시도한 qwen3-embedding-4b-mxfp8 은 LM Studio가 type:llm 으로 인식해
        임베딩 서빙 불가였음. type:embeddings 인 text-embedding-qwen3-embedding-4b 사용.
```

## 6. 인프라 (PGVector) [J]
환경이 둘로 나뉜다. **로컬은 Docker Desktop GUI, 서버는 Mac mini orbstack +
docker-compose(`chs-network`)**.
```
  [2026-06-09 현행]  공유 PG 1개를 운영 Mac mini(orbstack)에 두고 로컬·운영이 공유.
               · 운영 앱: jdbc:postgresql://chs-pgvector:5432/...  (chs-network 내부)
               · 로컬 앱: jdbc:postgresql://100.69.229.3:5432/...  (Tailscale)
               · 색인은 소스가 있는 로컬에서만 실행 → 운영 소스 마운트 불필요([H] 소멸)
               · 로컬 Docker Desktop pgvector 는 폐기(삭제). 상세는 10.5.
  (당초 계획) 로컬=Docker Desktop GUI, 서버=docker-compose 별도 기동 → 위로 대체됨.
```
서버 compose에 추가할 서비스 골격(컨테이너명=네트워크 내부 호스트명):
```yaml
  chs-pgvector:
    image: pgvector/pgvector:pg16
    container_name: chs-pgvector
    networks: [chs-network]
    environment:
      POSTGRES_PASSWORD: ${PG_VECTOR_PW}   # .env + jasypt 대상
      POSTGRES_DB: chs_vector
    volumes:
      - chs-pgvector-data:/var/lib/postgresql/data   # 색인 영속화
    restart: always
```
- pgvector 확장 포함 이미지 사용. 비밀번호/접속값은 .env + jasypt 암호화 대상.
- 로컬/서버 접속 호스트 차이는 프로파일(application-local / -prod)로 분리.

## 7. 컴포넌트 상세
- **CodebaseIndexingService**: 색인 대상 수집 → `TokenTextSplitter`로 청킹 → 메타데이터(파일경로) 부착 → `VectorStore.add()`.
  - [H] **jar 배포 색인 경로 전략**: 빌드된 jar 내부에서는 `springboot/src` 같은
    소스 디렉터리가 존재하지 않는다. 두 방식 중 택1을 명시한다.
    ```
      1) classpath 리소스 색인 — 색인 대상을 resources 하위로 포함시켜
         PathMatchingResourcePatternResolver(classpath*:) 로 읽기.
         장점: jar 단독 배포 / 단점: 빌드 산출물에 소스 사본 포함 필요.
      2) 외부 파일시스템 경로 색인 — chs.chatbot.index-root 프로퍼티로 절대경로
         주입(로컬=레포 경로, 서버=마운트 경로).
         장점: 실제 소스 그대로 / 단점: 배포 시 소스 경로 마운트 필요.
    ```
    > MVP 권장: 로컬은 (2) 파일시스템 경로, 서버 배포는 마운트 경로 주입. 구현 전 확정.
  - [K] `TokenTextSplitter`는 Java 구조(메서드/클래스 경계)를 무시해 청크가 잘릴 수 있음.
    MVP에서는 **비차단**으로 두고, 검색 품질 저하 시 코드 인지 분할로 교체(개선 과제).
- **ChatbotService**: `ChatClient` + `QuestionAnswerAdvisor`(검색→프롬프트 주입) 구성. 시스템 프롬프트로 "근거 없으면 모른다고 답하라" 지시.
- **ChatbotController**: `GET /chatbot`(화면), `POST /api/chat`(질의). 일반 사용 경로(permitAll).
- **ChatbotAdminController**: 색인은 고비용 작업이므로 **인증·권한 보호 경로**로 분리.
```
  POST /api/admin/chatbot/reindex      → 비동기 색인 시작, 작업ID 즉시 반환(202)
  GET  /api/admin/chatbot/reindex/{id} → 작업 상태 조회(진행/완료/문서수/오류)
```
  - [G] 실측: SecurityConfig.java:120 의 `/api/admin/**` →
    `.hasAnyAuthority("ADMIN_ACCESS")`로 **이미 보호됨**. 경로만 `/api/admin/...`에
    두면 인증·권한이 자동 적용된다(추가 보안설정·화이트리스트 변경 불필요).
  - [F] **reindex 재색인 정책 = 전체 삭제 후 재적재(full rebuild) + 동시실행 잠금**:
    ```
      reindex 호출 → AtomicBoolean/락 획득 시도
        ├─ 이미 실행 중 → 409 Conflict 반환(중복 실행 차단)
        └─ 획득 성공 → @Async 백그라운드:
              기존 벡터 전체 DELETE → 전체 재색인 → 작업상태=완료 → 락 해제
    ```
    > 중복 방지(같은 청크 재삽입)와 잔존 벡터 제거를 한 번에 해결. 색인 중
    > 짧은 검색 공백은 MVP에서 수용. ReindexJob DTO에 상태/문서수/오류 보관.
- **chat.html**: Thymeleaf + fetch 기반 단순 채팅 UI.

## 8. 단계별 실행 & 검증
```
1. 의존성/설정 추가        → 검증: 앱 부팅 성공, PgVector auto-config 제외([E]) 후
                                  pgVectorStore 빈 생성 / MySQL DataSource 정상
2. 임베딩 차원 실측([B])    → 검증: /v1/embeddings 응답 length 확정 후 dimensions 설정
3. PGVector 기동([J])      → 검증: 로컬 Desktop GUI / 서버 compose 5432 연결, 스키마 생성
4. 색인 서비스+엔드포인트   → 검증: ADMIN_ACCESS 토큰으로 reindex(202)→상태조회 문서수>0,
                                  무권한 호출은 403, 동시 호출은 409([F][G])
5. RAG 서비스+API          → 검증: 4개 성공기준 질문 통과
6. Thymeleaf 화면          → 검증: 브라우저에서 질의응답
```

## 9. 리스크 / 미해결
- 멀티 DataSource(MySQL+PG) 충돌 → [A][E] PG 전용 빈 수동등록 + auto-config 제외로 대응(완화됨).
- 임베딩 차원 불일치로 저장·검색 깨짐 → [B] 실측 차원 명시로 대응(완화됨).
- 고비용 reindex 노출 → [G] `/api/admin/**` 기존 ADMIN_ACCESS 보호 활용 + [F] 락/full rebuild로 대응(완화됨).
- jar 배포 시 소스 경로 부재 → [H] classpath/외부경로 전략 명시로 대응(구현 전 택1 확정).
- Spring AI 버전 표류 → [I] 단일 패치버전 고정으로 대응(구현 시 1.0.x 확정).
- LM Studio 임베딩 성능: 전체 색인 시간 미지수(1회성).
- gemma 모델의 한국어 코드설명 품질은 실제 호출 전까지 불확실.
- [K] TokenTextSplitter Java 구조 손실 → MVP 비차단, 검색품질 이슈 시 코드 인지 분할로 교체.

## 10. 구현 현황 (업데이트: 2026-06-08)

### 10.1 완료 — 계획서 1~6단계 전부 동작
```
1 의존성/설정        ✅ Spring AI BOM 1.0.8 + openai/pgvector starter, dimensions=2560
2 임베딩 차원 실측    ✅ 2560 (text-embedding-qwen3-embedding-4b)
3 PGVector 기동       ✅ Docker pgvector/pgvector:pg16 (chs_vector, 5432, vector 확장 0.8.2)
4 색인 서비스+API     ✅ reindex(202)→상태조회, 1066청크 색인 완료
5 RAG 서비스+API      ✅ 질문→벡터검색(top-6)→gemma 답변, 근거 파일 제시 검증됨
6 Thymeleaf 화면      ✅ chat.html (재색인 버튼 + 진행률 + 질문/답변)
```

### 10.2 실제 생성/변경 파일
```
[수정] build.gradle                              Spring AI BOM/starter + spring-ai-advisors-vector-store
[수정] SpringbootApplication.java                PgVectorStoreAutoConfiguration exclude (@EnableAsync 는 기존에 이미 있었음)
[수정] global/config/DataSourceConfig.java       batchJdbcTemplate 에 @Primary (아래 [장애1])
[수정] application.properties                     dimensions=2560, spring.http.client connect/read-timeout
[수정] application-local.properties              base-url(Tailscale), PG localhost, chs.chatbot.index-root
[신규] domain/chatbot/config/PgVectorConfig.java         PG 전용 빈, indexType(NONE) (아래 [장애2])
[신규] domain/chatbot/config/ChatbotConfig.java          ChatClient(환각억제 시스템 프롬프트)
[신규] domain/chatbot/config/HttpClientConfig.java       RestClient HTTP/1.1 강제 (아래 [장애5] — 핵심)
[신규] domain/chatbot/service/CodebaseIndexingService.java  색인 오케스트레이션(락 + 작업상태)
[신규] domain/chatbot/service/AsyncReindexRunner.java       비동기 색인 실행(@Async, 청크 256토큰, batchSize 4)
[신규] domain/chatbot/service/ChatbotService.java           RAG 질의(검색 + QuestionAnswerAdvisor)
[신규] domain/chatbot/controller/ChatbotAdminController.java   /api/admin/chatbot/reindex (+ 진행률)
[신규] domain/chatbot/controller/ChatbotController.java        GET /chatbot, POST /api/chat
[신규] domain/chatbot/dto/{ReindexJob,ChatRequest,ChatResponse}.java
[신규] resources/templates/chatbot/chat.html
```

### 10.3 해결한 장애 5건 (다음 작업자 참고용 — 같은 함정 회피)
```
[장애1] 부팅 실패: JdbcTemplate 빈 2개(batch/pgVector) 충돌
        → MetricCollectorService 등 @Qualifier 없이 JdbcTemplate 주입하던 곳이 모호해짐
        → 해결: DataSourceConfig.batchJdbcTemplate 에 @Primary (기존 단일 빈 동작 복원)

[장애2] 부팅 실패: "column cannot have more than 2000 dimensions for hnsw index"
        → 임베딩 2560차원 > pgvector HNSW/IVFFlat 인덱스 한계(2000)
        → 해결: PgVectorConfig 에서 indexType(PgIndexType.NONE) (전수 정확검색, 데이터 작아 충분)

[장애3] 색인 hang/timeout (증상): 임베딩 응답 무한 대기
        → 대응: batchSize 50→4, read-timeout 300s, 진행률(processed/total) 추가
        → (실제 근본 원인은 [장애5]였고, 이 조정들은 부수적이었음)

[장애4] 청크 크기: 큰 파일이 큰 청크(11KB)로 잘려 임베딩 47초/건
        → TokenTextSplitter(256토큰)로 축소 → 청크 1.5초/건 균일화 (448→1066청크)

[장애5] ★진짜 원인★ 색인이 단 한 건도 적재 안 됨(PG 항상 0)
        → JDK HttpClient 가 평문 http:// 에 HTTP/2 를 먼저 시도 → LM Studio(HTTP/1.1 only)가
          처리 못 해 요청 무한 hang. (curl 은 기본 HTTP/1.1 이라 1초에 정상 → 대조군으로 발견)
        → 해결: HttpClientConfig 의 RestClientCustomizer 로 HTTP/1.1 강제 (전역 적용, 부작용 없음)
        → 적용 즉시 정상 적재 시작, 1066청크 약 8분에 완료, RAG 정답 검증
```

### 10.4 남은 작업
```
[A] 유사도 임계값(similarityThreshold) 추가  — "안녕" 등 잡담에 무관한 근거가 붙는 문제
                                              (재색인 불필요, 재기동만). 미적용
[B] 브라우저 한글 질의 추가 테스트            — 인코딩 정상 확인됨(터미널만 깨졌던 것)
[C] 변경분 커밋                               — 아직 미커밋(작업트리에만 존재)
[D] 운영(prod) 배포 준비 [J]                  — 공유 PG는 운영 orbstack 에 구축 완료(10.5).
                                              남은 것: 앱 prod 배포 + application-prod 확인
                                              (HTTP/1.1 강제는 전역 빈이라 prod 자동 적용)
[E] 답변 속도(gemma 30~60초) 개선             — 선택
[F] 화면 다듬기(마크다운 렌더링 등)           — 선택
[G] page-context(현재 페이지 인식)            — 후보: { question, page } 전송 + 경로→파일 매핑
[H] 증분 색인 / batch-size 튜닝               — 후보: 현재 full-rebuild, batch=4
```
> 환경 실측: 로컬 PC, LM Studio = Mac mini(Tailscale 100.69.229.3:2345), 채팅 gemma-4-26b-a4b-it-mlx,
> 임베딩 text-embedding-qwen3-embedding-4b(2560차원). PG 비밀번호는 ${PGVECTOR_PASSWORD:postgres} 기본값 postgres.

### 10.5 작업 (2026-06-09) — 공유 PG 전환 · 프론트 색인 확장 · 화면 이관

#### (1) 벡터 DB를 운영 공유 1개로 통합
- 운영 Mac mini(orbstack)에 pgvector 컨테이너 1개만 두고 로컬·운영이 **공유**.
  (벡터는 코드에서 재생성 가능한 캐시 → 환경별 중복 색인 제거)
- 운영 앱: `jdbc:postgresql://chs-pgvector:5432/chs_vector` (chs-network 내부)
- 로컬 앱: `jdbc:postgresql://100.69.229.3:5432/chs_vector` (Tailscale, 포트 100.69.229.3:5432 노출)
- 색인은 소스가 있는 **로컬에서만** 실행 → 운영 소스 마운트 숙제([H]) 소멸.
- 로컬 Docker Desktop pgvector(컨테이너 + 익명볼륨 + 이미지) **완전 삭제**.
- 검증: DBeaver/Test-NetConnection 5432 OK, 앱 부팅 시 `pgvector-pool` 접속 + initializeSchema 정상
  (HTTP/1.1 강제 전역 빈 덕에 임베딩도 정상).

#### (2) 색인 범위 확장 — 백엔드 + 프론트
- `index-root`(단일) → `index-roots`(다중): `springboot/src` + `frontend/src`.
  - 소스 폴더만 지정 → `node_modules/build/dist` 를 **아예 안 밟음**(제외 로직 불필요, 폭증 위험 0).
- `includeExtensions` 에 `.tsx/.jsx/.ts/.js` 추가(.css 제외).
- `source-base`(레포 루트) 추가 → source 메타데이터를 `frontend/src/...`, `springboot/src/...`
  형태(레포 기준)로 기록 → 향후 page-context([G]) 기능에 재활용.
- 청크 1066 → **2069** (프론트 포함).
- 변경: `ChatbotProperties.java`, `CodebaseDocumentSource.java`, `application-local.properties`.

#### (3) 재색인+질의를 React admin test 로 이관, 옛 Thymeleaf 페이지 제거
- 신규 `/admin/test/chatbot` 탭(재색인 버튼+진행률 / 질의응답+근거).
  - frontend: `api/adminTest/chatbot.js`, `page/admin/test/ChatbotTestPage.jsx`(+`.module.css`),
    `AdminTestLayout.jsx`(탭 추가), `app/router/MainRouter.jsx`(라우트 추가).
- 옛 `templates/chatbot/chat.html` 삭제, `ChatbotController` 의 `GET /chatbot` 제거,
  `@Controller`→`@RestController`. **`POST /api/chat` 은 유지**(위젯/탭 공용).

#### (4) FloatingChatbot(우측 하단 위젯) 정리
- 답변 아래 **근거(sources) 표시 제거** → 위젯은 대화만. 근거는 admin test 탭에만 노출.
- 로딩 문구 강화: "답변 생성 중... (로컬 AI 모델이라 수십 초 걸릴 수 있어요)".
- 미사용 스타일 제거. (`/api/chat` 응답엔 sources 여전히 포함, 위젯이 안 그릴 뿐)

> 미커밋(작업트리). 환경: 로컬이 운영 공유 PG에 의존하므로 운영/ Tailscale 다운 시 로컬 검색도 영향.
