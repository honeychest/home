// [AGENT] Open Interest 폴링 서비스 — 1분마다 Binance API 호출, 리더만 실행
// 연관파일: OpenInterestRepository.java, SignalSseService.java, LeaderElectionService.java
// 주요메서드: pollOpenInterest() → @Scheduled(fixedRate = 60000)
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.OpenInterest;
import com.chs.springboot.domain.binance.repository.OpenInterestRepository;
import com.chs.springboot.global.monitor.health.HealthCheckCatalog;
import com.chs.springboot.global.monitor.health.HealthHeartbeat;
import com.chs.springboot.global.redis.LeaderElectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenInterestPollingService {

    private final LeaderElectionService leaderElectionService;
    private final OpenInterestRepository openInterestRepository;
    private final SignalSseService signalSseService;
    private final HealthHeartbeat healthHeartbeat;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String HEALTH_KEY = HealthCheckCatalog.SCHED_OPENINTEREST_POLL.key();

    private static final String OI_API_URL    = "https://fapi.binance.com/fapi/v1/openInterest";
    private static final String PRICE_API_URL = "https://fapi.binance.com/fapi/v1/ticker/price";
    private static final List<String> SYMBOLS = List.of("BTCUSDT", "ENAUSDT");

    @Scheduled(fixedRate = 60_000)
    public void pollOpenInterest() {
        if (!leaderElectionService.isLeader()) {
            return;
        }

        int okCount = 0;
        String lastError = null;
        for (String symbol : SYMBOLS) {
            try {
                String url = OI_API_URL + "?symbol=" + symbol;
                String response = restTemplate.getForObject(url, String.class);

                JsonNode node = objectMapper.readTree(response);
                String oiValue = node.get("openInterest").asText();
                // 1분 경계로 내림 — (symbol, collected_at_ms) 유니크 키와 짝을 이뤄 1분에 한 행이 남는다.
                // 예전에는 5분 경계였고, 그 탓에 1분마다 폴링하면서도 5번 중 4번이 유니크 키에 막혀 버려졌다.
                // 그 결과 OI 차트가 5분 간격이 되어 5분·30분 구간에서 1분봉 캔들과 밀도가 어긋났다 (2026-09-06).
                // 과거 구간은 OiBackfillService 가 바이낸스 5m klines 로 채우므로 계속 5분 격자다 — 1분 격자는 이 시점 이후만.
                long timestamp = node.get("time").asLong() / 60_000L * 60_000L;

                // 현재가 조회
                BigDecimal price = null;
                try {
                    String priceResponse = restTemplate.getForObject(PRICE_API_URL + "?symbol=" + symbol, String.class);
                    JsonNode priceNode = objectMapper.readTree(priceResponse);
                    price = new BigDecimal(priceNode.get("price").asText());
                } catch (Exception pe) {
                    log.warn("[OI Polling] {} 현재가 조회 실패: {}", symbol, pe.getMessage());
                }

                OpenInterest oi = new OpenInterest();
                oi.setSymbol(symbol);
                oi.setOpenInterest(new BigDecimal(oiValue));
                oi.setPrice(price);
                oi.setCollectedAtMs(timestamp);

                openInterestRepository.insertIgnoreDuplicate(oi);

                Map<String, Object> dto = new HashMap<>();
                dto.put("symbol",        symbol);
                dto.put("openInterest",  oiValue);
                dto.put("collectedAtMs", timestamp);
                dto.put("price",         price != null ? price.toPlainString() : null);
                signalSseService.broadcastOiUpdate(dto);
                okCount++;
            } catch (Exception e) {
                lastError = symbol + ": " + e.getMessage();
                log.warn("[OI Polling] {} 실패: {}", symbol, e.getMessage());
            }
        }

        // 헬스 하트비트: 한 심볼이라도 성공하면 beat, 전부 실패면 fail
        if (okCount > 0) {
            healthHeartbeat.beat(HEALTH_KEY);
        } else {
            healthHeartbeat.fail(HEALTH_KEY, lastError != null ? lastError : "모든 심볼 수집 실패");
        }
    }
}
