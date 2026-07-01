// [AGENT] 역할: aggTrade 큐 → DB 플러시 스케줄러 (@Scheduled fixedRate=1초) | 연관파일: AggTradeStorageService.java, AggTradeConfigService.java | 핵심: 1초마다 tick()에서 flushIntervalSec 경과 확인 후 storageService.doFlush() 비동기 실행
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.global.monitor.health.HealthCheckCatalog;
import com.chs.springboot.global.monitor.health.HealthHeartbeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AggTradeFlushScheduler {

    private static final Logger log = LoggerFactory.getLogger(AggTradeFlushScheduler.class);
    private static final String HEALTH_KEY = HealthCheckCatalog.PIPE_AGGTRADE_FLUSH.key();

    private final AggTradeStorageService storageService;
    private final AggTradeConfigService configService;
    private final HealthHeartbeat healthHeartbeat;

    private volatile long lastFlushEpochSec = 0L;

    public AggTradeFlushScheduler(AggTradeStorageService storageService,
                                  AggTradeConfigService configService,
                                  HealthHeartbeat healthHeartbeat) {
        this.storageService = storageService;
        this.configService = configService;
        this.healthHeartbeat = healthHeartbeat;
    }

    @Scheduled(fixedRate = 1000L)
    public void tick() {
        long nowSec = System.currentTimeMillis() / 1000L;
        int interval = configService.getFlushIntervalSec();
        if (nowSec - lastFlushEpochSec < interval) {
            return;
        }
        storageService.getFlushExecutor().submit(() -> {
            storageService.doFlush();
            lastFlushEpochSec = System.currentTimeMillis() / 1000L;
            healthHeartbeat.beat(HEALTH_KEY);
        });
    }
}

