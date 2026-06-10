// [AGENT] 임계 지속 초과 알림 + 쿨다운 + AlertHistory 저장
package com.chs.springboot.global.monitor.service;

import com.chs.springboot.global.monitor.dto.MetricSnapshot;
import com.chs.springboot.global.monitor.entity.AlertHistory;
import com.chs.springboot.global.monitor.feed.FeedHealthRegistry;
import com.chs.springboot.global.monitor.feed.FeedStatus;
import com.chs.springboot.global.monitor.repository.AlertHistoryRepository;
import com.chs.springboot.global.telegram.TelegramProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final StringRedisTemplate redisTemplate;
    private final TelegramProvider telegramProvider;
    private final AlertHistoryRepository alertHistoryRepository;

    @Value("${monitor.alert-history.source-env:${spring.profiles.active:local}}")
    private String sourceEnv;

    private final ConcurrentHashMap<String, AtomicInteger> durationCounters = new ConcurrentHashMap<>();

    public void evaluate(MetricSnapshot snapshot) {
        if (snapshot == null) return;

        if (isSilenced()) {
            return;
        }

        // CPU/RAM: 80% 이상 30초 지속 시 알림
        evaluateCritical(AlertHistory.MetricType.CPU, snapshot.cpu(), 80d, snapshot.containerId());
        evaluateCritical(AlertHistory.MetricType.RAM, snapshot.ram(), 80d, snapshot.containerId());

        // DISK: 80% 이상이면 하루에 한 번만 알림
        evaluateDiskDaily(snapshot.disk(), 80d, snapshot.containerId());

        evaluateFeedAlerts(snapshot.feeds());
    }

    private void evaluateCritical(AlertHistory.MetricType type, Double value, double threshold, String containerId) {
        if (value == null) {
            durationCounters.computeIfAbsent(type.name(), k -> new AtomicInteger(0)).set(0);
            return;
        }

        int durationCycles = 6; // 5초 * 6 = 30초
        int cooldownSec = 3600;

        AtomicInteger counter = durationCounters.computeIfAbsent(type.name(), k -> new AtomicInteger(0));
        if (value >= threshold) {
            int c = counter.incrementAndGet();
            if (c < durationCycles) {
                return;
            }

            String cooldownKey = "monitor:alert:cooldown:" + type.name();
            String already = redisTemplate.opsForValue().get(cooldownKey);
            if (already != null) {
                return;
            }

            sendAndStore(type, value, threshold, durationCycles * 5, cooldownKey, cooldownSec, containerId);
        } else {
            counter.set(0);
        }
    }

    private void evaluateDiskDaily(Double value, double threshold, String containerId) {
        if (value == null) {
            return;
        }
        if (value < threshold) {
            return;
        }

        String cooldownKey = "monitor:alert:cooldown:DISK_DAILY";
        String already = redisTemplate.opsForValue().get(cooldownKey);
        if (already != null) {
            return;
        }

        int cooldownSec = 86400; // 하루에 한 번
        sendAndStore(AlertHistory.MetricType.DISK, value, threshold, 0, cooldownKey, cooldownSec, containerId);
    }

    private boolean isSilenced() {
        try {
            String v = redisTemplate.opsForValue().get("monitor:silence");
            return "ON".equalsIgnoreCase(v);
        } catch (Exception e) {
            return false;
        }
    }

    private void evaluateFeedAlerts(java.util.List<FeedHealthRegistry.FeedHealth> feeds) {
        if (feeds == null || feeds.isEmpty()) {
            return;
        }
        for (FeedHealthRegistry.FeedHealth feed : feeds) {
            if (feed == null) {
                continue;
            }
            FeedStatus status = feed.status();
            if (status != FeedStatus.STALE && status != FeedStatus.DOWN) {
                continue;
            }

            String feedId = feed.feedId();
            String cooldownKey = "monitor:alert:cooldown:FEED:" + feedId + ":" + status.name();
            String already = redisTemplate.opsForValue().get(cooldownKey);
            if (already != null) {
                continue;
            }

            AlertHistory history = new AlertHistory();
            history.setMetricType(feedMetricType(feedId));
            history.setValue(feed.secondsSinceLastMessage() == null ? 0d : feed.secondsSinceLastMessage().doubleValue());
            history.setThreshold(status == FeedStatus.DOWN ? 30d : 10d);
            history.setDurationSec(feed.secondsSinceLastMessage() == null ? 0 : Math.toIntExact(feed.secondsSinceLastMessage()));
            history.setSeverity(status == FeedStatus.DOWN ? AlertHistory.Severity.CRITICAL : AlertHistory.Severity.WARN);
            history.setSentAt(LocalDateTime.now());
            history.setSourceEnv(normalizedSourceEnv());
            history.setMemo("[%s] %s / lastMessageAt=%s / receivedCount=%d"
                    .formatted(feedId, status.name(), feed.lastMessageAtEpochMs(), feed.receivedCount()));
            alertHistoryRepository.save(history);
            redisTemplate.opsForValue().set(cooldownKey, "1", 3600, TimeUnit.SECONDS);
        }
    }

    private static AlertHistory.MetricType feedMetricType(String feedId) {
        return switch (feedId) {
            case "binance-ticker" -> AlertHistory.MetricType.FEED_BINANCE_TICKER;
            case "binance-aggTrade" -> AlertHistory.MetricType.FEED_BINANCE_AGG;
            case "upbit" -> AlertHistory.MetricType.FEED_UPBIT;
            default -> AlertHistory.MetricType.API_ERROR;
        };
    }

    private void sendAndStore(
            AlertHistory.MetricType type,
            double value,
            double threshold,
            int durationSec,
            String cooldownKey,
            int cooldownSec,
            String containerId
    ) {
        try {
            String titleMetric = switch (type) {
                case CPU -> "CPU";
                case RAM -> "RAM";
                case DISK -> "DISK";
                case REDIS_QUEUE -> "Redis 큐";
                case API_ERROR -> "API 에러율";
                case FEED_BINANCE_TICKER -> "피드(binance-ticker)";
                case FEED_BINANCE_AGG -> "피드(binance-aggTrade)";
                case FEED_UPBIT -> "피드(upbit)";
            };

            String sentAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String msg = """
                    🔴 [긴급] %s 과부하
                    현재값: %.1f%% / 임계값: %.1f%%
                    지속시간: %d분
                    발생: %s
                    서버: %s
                    """.formatted(titleMetric, value, threshold, Math.max(1, durationSec / 60), sentAt, containerId != null ? containerId : "UNKNOWN")
                    .trim();

            telegramProvider.sendMessage(msg);

            redisTemplate.opsForValue().set(cooldownKey, "1", cooldownSec, TimeUnit.SECONDS);

            AlertHistory h = new AlertHistory();
            h.setMetricType(type);
            h.setValue(value);
            h.setThreshold(threshold);
            h.setDurationSec(durationSec);
            h.setSeverity(AlertHistory.Severity.CRITICAL);
            h.setSentAt(LocalDateTime.now());
            h.setSourceEnv(normalizedSourceEnv());
            alertHistoryRepository.save(h);
        } catch (Exception e) {
            log.warn("AlertService send/store failed: {}", e.getMessage());
        }
    }

    private String normalizedSourceEnv() {
        if (sourceEnv == null || sourceEnv.isBlank()) {
            return "local";
        }
        String normalized = sourceEnv.toLowerCase().trim();
        if (normalized.contains("prod")) {
            return "prod";
        }
        if (normalized.contains("local")) {
            return "local";
        }
        return normalized.split(",")[0].trim();
    }
}

