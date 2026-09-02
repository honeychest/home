// [AGENT] SSE 팬아웃(emitter 순회 전송)을 호출 스레드에서 떼어내는 공용 실행기.
// 웹소켓 수신 스레드 등에서 emitter.send()를 동기로 돌리면 느린 클라이언트 하나가
// 그 스레드를 막는다(실측: SignalSseService). emitter 목록·직렬화·이벤트 이름 같은
// 서비스별 로직은 각 서비스가 그대로 갖고, 이 클래스는 실행기 생명주기만 공용화한다.
// 연관: SignalSseService, RawTickSseService, BinanceTradeSseService
package com.chs.springboot.domain.binance.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class AsyncSseDispatcher {

    private final ExecutorService executor;

    AsyncSseDispatcher(String threadName) {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
    }

    void dispatch(Runnable task) {
        executor.execute(task);
    }

    void shutdown() {
        executor.shutdownNow();
    }
}
