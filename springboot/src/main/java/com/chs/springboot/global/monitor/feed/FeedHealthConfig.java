// [AGENT] 역할: FeedHealthRegistry 빈 생성 + 감시 대상 피드/임계 중앙 등록 | 연관파일: FeedHealthRegistry.java, BinanceStreamService/AggTradeStreamService/UpbitStreamService
// Purpose: 꾸준한 하트비트가 있는 업스트림 피드(ticker/aggTrade/upbit)만 freshness 감시 대상으로 등록한다.
package com.chs.springboot.global.monitor.feed;

import com.chs.springboot.global.monitor.health.StatusLadder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class FeedHealthConfig {

    public static final String BINANCE_TICKER = "binance-ticker";
    public static final String BINANCE_AGG_TRADE = "binance-aggTrade";
    public static final String UPBIT = "upbit";

    // 기동 유예(초): 재시작/리더전환 직후 첫 수신 전의 가짜 DOWN·알림 방지. 이 시간 지나도 미수신이면 DOWN.
    @Bean
    public FeedHealthRegistry feedHealthRegistry(
            Clock clock,
            @Value("${monitor.health.feed-grace-seconds:30}") long feedGraceSeconds) {
        FeedHealthRegistry registry = new FeedHealthRegistry(clock, feedGraceSeconds);
        registry.register(BINANCE_TICKER, StatusLadder.FEED_SECONDS);
        registry.register(BINANCE_AGG_TRADE, StatusLadder.FEED_SECONDS);
        registry.register(UPBIT, StatusLadder.FEED_SECONDS);
        return registry;
    }
}
