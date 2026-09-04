// [AGENT] 백엔드 패턴 카탈로그 원장 (사람용 교보재) — admin > "백엔드 패턴" 모달에서 열람.
// 에이전트용 인덱스(키+실물 경로)는 springboot/AGENTS.md 표가 원본 — 패턴 적립 시 두 곳 모두 갱신.
// skeleton 은 실물 복사가 아니라 패턴의 뼈대 증류본 — 실물이 리팩토링돼도 여기는 패턴이 바뀔 때만 고친다.
export const BACKEND_PATTERN_GROUPS = ['비동기 처리'];

export const BACKEND_PATTERNS = [
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
