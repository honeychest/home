// [AGENT] 역할: FeedHealthRegistry 빈 생성 + 감시 대상 피드/임계 중앙 등록 | 연관파일: FeedHealthRegistry.java, BinanceStreamService/AggTradeStreamService/UpbitStreamService
// Purpose: 꾸준한 하트비트가 있는 업스트림 피드(ticker/aggTrade/upbit)만 freshness 감시 대상으로 등록한다.
package com.chs.springboot.global.monitor.feed;

import com.chs.springboot.global.monitor.health.StatusLadder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class FeedHealthConfig {

    public static final String BINANCE_TICKER = "binance-ticker";
    public static final String BINANCE_AGG_TRADE = "binance-aggTrade";
    public static final String UPBIT = "upbit";

    @Bean
    public FeedHealthRegistry feedHealthRegistry(Clock clock) {
        FeedHealthRegistry registry = new FeedHealthRegistry(clock);
        registry.register(BINANCE_TICKER, StatusLadder.FEED_SECONDS);
        registry.register(BINANCE_AGG_TRADE, StatusLadder.FEED_SECONDS);
        registry.register(UPBIT, StatusLadder.FEED_SECONDS);
        return registry;
    }
}
