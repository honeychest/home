// [AGENT] 백엔드 패턴 카탈로그 원장 (사람용 교보재) — admin > "백엔드 패턴" 모달에서 열람.
// 에이전트용 인덱스(키+실물 경로)는 springboot/AGENTS.md 표가 원본 — 패턴 적립 시 두 곳 모두 갱신.
// skeleton 은 실물 복사가 아니라 패턴의 뼈대 증류본 — 실물이 리팩토링돼도 여기는 패턴이 바뀔 때만 고친다.
export const BACKEND_PATTERN_GROUPS = ['판정', '외부 연동', '비동기 처리', 'DB'];

export const BACKEND_PATTERNS = [
    {
        key: 'pattern-pure-rules',
        label: '순수 판정 분리',
        group: '판정',
        intent: '임계값·분류·매칭 같은 "판단"을 컨트롤러에서 떼어내 순수 함수로',
        when: '"7분 넘으면 컷" 같은 규칙이 생길 때. if 문을 컨트롤러에 쓰고 싶어지면 이 패턴.',
        flow: [
            '컨트롤러는 입력을 받고 결과를 응답하는 일만 한다 (판단 없음)',
            '판단은 XxxRules 클래스의 static 순수 함수가 한다 — 입력만 보고 답을 돌려줄 뿐, DB·HTTP·시간에 안 기댐',
            '컨트롤러는 그 답을 받아 저장하거나 응답할 뿐',
            '테스트는 Rules 만 따로 — 서버 안 띄우고 값 넣고 값 확인으로 끝',
        ],
        skeleton: `// 판정만 모아둔 순수 클래스 — DB/HTTP 없이 테스트
final class XxxRules {
    private XxxRules() {}

    /** 길이를 모르면(null) 컷하지 않는다 — 막는 것보다 낫다 */
    static String initialStatus(Integer durationSeconds, int maxMinutes) {
        boolean tooLong = durationSeconds != null
                && durationSeconds > maxMinutes * 60;
        return tooLong ? "TOO_LONG" : "WAITING";
    }
}

// 컨트롤러에서는:
String status = XxxRules.initialStatus(meta.duration(), props.getMaxMinutes());`,
        examples: [
            '[gikka 저장소] com/chs/gikka/registration/RegistrationRules.java',
            '[gikka 저장소] com/chs/gikka/fridge/FridgeRepository.java (rankFrequent)',
            '[gikka 저장소] com/chs/gikka/registration/GeminiRecipeExtractor.java (parseEnvelope)',
        ],
    },
    {
        key: 'pattern-rest-seam',
        label: '외부 HTTP 테스트 시임',
        group: '외부 연동',
        intent: '외부 API 호출 코드를 실제 서버 없이 테스트할 수 있게 이음새를 만들기',
        when: '유튜브·Gemini 처럼 바깥 서버를 호출하는 클래스를 새로 만들 때. new RestClient 를 직접 만들고 싶어지면 이 패턴.',
        flow: [
            'RestClient 를 클래스 안에서 직접 만들지 않고, 생성자로 Builder 를 주입받는다',
            '운영에서는 스프링이 진짜 Builder 를 넣어줌 — 코드는 그대로',
            '테스트에서는 MockRestServiceServer 가 그 Builder 에 끼어들어 가짜 서버가 됨',
            '그래서 "404가 오면?", "이상한 JSON 이 오면?" 같은 시나리오를 실제 네트워크 없이 검증',
        ],
        skeleton: `@Component
class XxxClient {
    private final RestClient rest;

    // Builder 주입이 핵심 — 테스트가 이 틈으로 가짜 서버를 끼워 넣는다
    XxxClient(RestClient.Builder builder) {
        this.rest = builder.baseUrl("https://api.example.com").build();
    }
}

// 테스트에서:
MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
server.expect(requestTo(...)).andRespond(withSuccess(json, APPLICATION_JSON));`,
        examples: [
            '[gikka 저장소] com/chs/gikka/registration/GeminiRecipeExtractor.java',
            '[gikka 저장소] com/chs/gikka/registration/YoutubeMetadataClient.java',
        ],
    },
    {
        key: 'pattern-port-adapter',
        label: '외부 시스템 인터페이스 격리',
        group: '외부 연동',
        intent: '외부 시스템(AI·메타 조회 등)을 인터페이스 뒤에 숨겨 교체 가능하게',
        when: '"나중에 Gemini 를 다른 모델로 바꿀 수도" 싶은 의존이 생길 때. 컨트롤러가 구체 클래스명을 알게 하고 싶지 않을 때.',
        flow: [
            '컨트롤러/워커는 인터페이스(항구)만 안다 — "레시피를 추출해줘" 라는 약속만',
            '실제 일은 구현체(배)가 한다 — GeminiRecipeExtractor 등',
            '모델을 바꾸면 구현체 클래스 하나만 새로 만들어 갈아끼움 — 호출하는 쪽은 무변경',
            '테스트에서는 가짜 구현체를 꽂아 외부 없이 흐름 검증',
            '배가 둘 이상이면 "어느 배를 먼저 띄울까"(라우팅)를 어디 둘지가 갈린다 — 아래 참고',
        ],
        skeleton: `// 약속(항구): 호출하는 쪽은 이것만 안다.
// 어휘(결과 타입·상수)와 두 배가 공유하는 응답 파싱도 항구가 소유한다 —
// 구현체 하나가 쥐고 있으면 다른 구현체가 그 구현체를 들여다보게 되고, 결국 못 빼낸다.
interface RecipeExtractor {
    ExtractionResult extract(String videoUrl);
}

// 구현(배): 갈아끼우는 대상
@Component
class GeminiRecipeExtractor implements RecipeExtractor {
    @Override
    public ExtractionResult extract(String videoUrl) { ... }
}

// ── 배가 둘 이상일 때: 라우팅을 어디 두나 ──

// (가) 순서가 하나뿐이면 @Primary 라우터 빈 하나로 끝난다.
//      "항상 로컬 먼저, 안 되면 Gemini" — 호출부는 라우팅을 아예 모른다.
@Primary @Component
class HybridRecipeExtractor implements RecipeExtractor { ... }

// (나) 호출부마다 순서가 반대면 @Primary 로는 못 묶는다 (빈은 하나뿐이니까).
//      순서를 인자로 받는 라우터를 둔다.
//      실물: 재료 사전 판정 — 워커(상시)는 무료 한도를 아끼려 로컬 우선,
//            오너의 [AI 점검] 버튼은 지금 최고 품질을 원해 누른 것이라 Gemini 우선.
@Component
class DictionaryJudge {
    enum Order { LOCAL_FIRST, GEMINI_FIRST }

    List<Proposal> propose(Order order) {
        IngredientJudge primary = order == Order.LOCAL_FIRST ? local : gemini;
        IngredientJudge secondary = order == Order.LOCAL_FIRST ? gemini : local;
        try {
            return primary.audit(...);
        } catch (채널불가 e) {
            try {
                return secondary.audit(...);
            } catch (채널불가 ignored) {
                throw e; // 1차 예외를 그대로 — 호출부의 에러 계약(503)이 그 타입에 걸려 있다
            }
        }
    }
}`,
        examples: [
            '[gikka 저장소] com/chs/gikka/registration/RecipeExtractor.java',
            '[gikka 저장소] com/chs/gikka/registration/HybridRecipeExtractor.java',
            '[gikka 저장소] com/chs/gikka/registration/IngredientJudge.java',
            '[gikka 저장소] com/chs/gikka/registration/DictionaryJudge.java',
            '[gikka 저장소] com/chs/gikka/registration/VideoMetadataClient.java',
        ],
    },
    {
        key: 'pattern-failover-notify',
        label: '폴백 전환 + 알림',
        group: '외부 연동',
        intent: '외부 의존이 막혔을 때 자동으로 대체 경로로 전환하고 사람에게 알리기',
        when: '외부 모델·API 가 "언젠가 폐쇄/변경될 수 있는" 것일 때. 새벽에 조용히 죽는 대신 스스로 전환+보고하게.',
        flow: [
            '평소에는 설정된 기본 모델을 호출',
            '404(폐쇄) 같은 신호를 감지하면 폴백 모델로 전환 — 재기동 전까지 유지(매번 404 낭비 방지)',
            '전환 순간 텔레그램으로 알림 — 사람이 아침에 알고 설정을 고침',
            '2인스턴스는 각자 판정 — 공유 상태 없이 인스턴스별 전환',
        ],
        skeleton: `// 전환 상태는 volatile 필드 — 재기동 전까지 유지
private volatile String activeModel;

try {
    return call(activeModel, body);
} catch (HttpClientErrorException e) {
    if (e.getStatusCode() == NOT_FOUND && !isFallback()) {
        activeModel = properties.getFallbackModel();   // 전환
        notifier.notify("모델 폐쇄 감지 — 폴백 전환: " + activeModel);
        return call(activeModel, body);                // 즉시 재시도
    }
    throw e;
}`,
        examples: [
            '[gikka 저장소] com/chs/gikka/registration/GeminiRecipeExtractor.java (페일오버)',
            '[gikka 저장소] com/chs/gikka/registration/GikkaTelegramNotifier.java',
        ],
    },
    {
        key: 'pattern-queue-worker',
        label: 'DB 대기열 + 단일 워커',
        group: '비동기 처리',
        intent: '오래 걸리는 일을 요청과 분리 — 요청은 즉시 응답, 처리는 뒤에서',
        when: 'AI 분석처럼 수십 초 걸리는 작업. 요청 스레드에서 기다리게 하면 타임아웃·풀 고갈 — 그때 이 패턴.',
        flow: [
            '요청이 오면 DB 에 "대기(WAITING)" 행만 넣고 바로 응답 — 사용자는 안 기다림',
            '@Scheduled 워커가 주기적으로 대기 행을 집어 상태를 "분석 중"으로 바꾸고 처리',
            '끝나면 "완료/실패"로 갱신 — 프론트는 목록 폴링으로 진행 상황을 봄',
            '앱이 2인스턴스라 워커도 2개 — 상태 전이(WAITING→ANALYZING)를 원자적 UPDATE 로 잡아 중복 처리 방지',
        ],
        skeleton: `// 1) 등록: 대기 행 삽입 후 즉시 응답
repository.insert(userId, videoId, url, "WAITING", ...);

// 2) 워커: 주기 실행 — 대기 1건을 원자적으로 선점
@Scheduled(fixedDelay = 5_000)
public void processNext() {
    // UPDATE ... SET status='ANALYZING' WHERE status='WAITING' LIMIT 1
    // → 성공한 인스턴스만 처리 (2인스턴스 중복 안전)
    var item = repository.claimNextWaiting();
    if (item == null) return;
    try { ...처리...; repository.markDone(item); }
    catch (Exception e) { repository.markFailed(item); }
}`,
        examples: [
            '[gikka 저장소] com/chs/gikka/registration/RegistrationWorker.java',
            '[gikka 저장소] com/chs/gikka/registration/RegistrationController.java (등록부)',
        ],
    },
    {
        key: 'pattern-tx-template',
        label: '보조 DB 트랜잭션',
        group: 'DB',
        intent: '두 번째 DB(gikka)의 트랜잭션을 기존 도메인을 깨지 않고 다루기',
        when: 'gikka(PostgreSQL) 쪽에서 여러 쿼리를 묶어야 할 때. @Transactional 을 붙이고 싶어지면 멈추고 이 패턴.',
        flow: [
            '주의: gikka 용 TransactionManager 를 스프링 빈으로 등록하면 안 됨 — 부트 기본 자동구성이 꺼져 기존 도메인 @Transactional 전체가 깨짐 (2026-07-10 실측 사고)',
            '대신 GikkaDataSourceConfig 가 만들어 둔 TransactionTemplate(gikkaTxTemplate)을 주입받는다',
            '묶고 싶은 쿼리들을 execute(...) 람다 안에 넣으면 그 블록이 하나의 트랜잭션',
            '람다가 정상 종료하면 커밋, 예외가 나오면 롤백 — 끝',
        ],
        skeleton: `// gikka 쪽 다건 작업을 하나의 트랜잭션으로
private final TransactionTemplate gikkaTxTemplate;

int added = gikkaTxTemplate.execute(status -> {
    int count = 0;
    for (var item : items) {
        if (repository.insert(...)) count++;
    }
    return count;   // 정상 반환 = 커밋, 예외 = 롤백
});`,
        examples: [
            '[gikka 저장소] com/chs/gikka/config/GikkaDataSourceConfig.java (gikkaTxTemplate)',
        ],
    },
    {
        key: 'pattern-async-sse-dispatch',
        label: 'SSE 팬아웃 비동기 분리',
        group: '비동기 처리',
        intent: 'SSE(emitter) 방송을 웹소켓 수신 등 호출 스레드에서 떼어내기',
        when: '웹소켓 콜백·수신 루프 안에서 emitter.send() 를 순회 호출하고 싶어질 때. 느린 클라이언트 하나가 그 스레드를 막아 물량이 몰리면 재연결이 반복된다(실측: binance aggTrade).',
        flow: [
            'emitter 목록·직렬화·이벤트 이름·죽은 emitter 처리는 서비스가 그대로 갖는다 — 서비스마다 다르다',
            'broadcast() 는 실행기(AsyncSseDispatcher)에 작업만 넘기고 즉시 리턴 — 호출 스레드는 안 기다림',
            '실제 emitter.send() 순회는 서비스 전용 단일 데몬 스레드에서 돈다',
            '@PreDestroy 로 실행기를 종료 — 스레드 이름은 서비스마다 다르게(로그 구분용)',
        ],
        skeleton: `// 공용: 실행기 생명주기만 (emitter 로직은 안 건드림)
class AsyncSseDispatcher {
    private final ExecutorService executor;
    AsyncSseDispatcher(String threadName) {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
    }
    void dispatch(Runnable task) { executor.execute(task); }
    void shutdown() { executor.shutdownNow(); }
}

// 서비스: broadcast() 는 넘기기만, 실제 전송은 doBroadcast() 로 분리
private final AsyncSseDispatcher dispatcher = new AsyncSseDispatcher("xxx-sse-broadcast");

public void broadcast(Dto dto) {
    if (emitters.isEmpty()) return;
    dispatcher.dispatch(() -> doBroadcast(dto));   // 호출 스레드(웹소켓 등)는 여기서 안 막힘
}

private void doBroadcast(Dto dto) {
    // 기존 emitter 순회 send() 로직 그대로
}

@PreDestroy
public void shutdown() { dispatcher.shutdown(); }`,
        examples: [
            'springboot/domain/binance/service/AsyncSseDispatcher.java',
            'springboot/domain/binance/service/SignalSseService.java',
            'springboot/domain/binance/service/RawTickSseService.java',
            'springboot/domain/binance/service/BinanceTradeSseService.java',
        ],
    },
];
