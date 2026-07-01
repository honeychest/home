// [AGENT] 뉴스 RSS 수집 서비스 — 5분 캐싱, 중복 제거
package com.chs.springboot.global.news.service;

import com.chs.springboot.global.monitor.health.HealthCheckCatalog;
import com.chs.springboot.global.monitor.health.HealthHeartbeat;
import com.chs.springboot.global.news.dto.NewsItem;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
public class NewsService {

    private static final Logger log = LoggerFactory.getLogger(NewsService.class);

    // RSS 소스 목록 — {url, source명, 카테고리}
    private static final List<String[]> SOURCES = List.of(
            new String[]{"https://www.yna.co.kr/rss/economy.xml", "연합뉴스", "경제"},
            new String[]{"https://www.yna.co.kr/rss/news.xml", "연합뉴스", "최신"},
            new String[]{"https://news.google.com/rss/topics/CAAqJggKIiBDQkFTRWdvSUwyMHZNRGx6TVdZU0FtdHZHZ0pMVWlnQVAB?hl=ko&gl=KR&ceid=KR:ko",
                    "구글", "경제"},
            new String[]{"https://news.google.com/rss/topics/CAAqJggKIiBDQkFTRWdvSUwyMHZNRGRqTVhZU0FtdHZHZ0pMVWlnQVAB?hl=ko&gl=KR&ceid=KR:ko",
                    "구글", "IT"},
            new String[]{"https://news.google.com/rss?hl=ko&gl=KR&ceid=KR:ko", "구글", "인기"},
            new String[]{"https://feeds.feedburner.com/geeknews-feed", "긱뉴스", "IT"}
    );

    // 캐시 — 마지막으로 수집한 뉴스 목록을 메모리에 보관
    // volatile: 멀티스레드 환경에서 캐시 갱신이 즉시 반영되도록 보장
    private volatile List<NewsItem> cache = List.of();

    private static final String HEALTH_KEY = HealthCheckCatalog.SCHED_NEWS.key();
    private final HealthHeartbeat healthHeartbeat;

    public NewsService(HealthHeartbeat healthHeartbeat) {
        this.healthHeartbeat = healthHeartbeat;
    }

    // 앱 시작 시 1회 즉시 수집, 이후 5분마다 반복
    // fixedDelay: 이전 실행 완료 후 5분 뒤 재실행 (RSS 서버 부하 방지)
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void refresh() {
        List<NewsItem> collected = new ArrayList<>();
        int okCount = 0;
        String lastError = null;
        for (String[] src : SOURCES) {
            try {
                collected.addAll(fetch(src[0], src[1], src[2]));
                okCount++;
            } catch (Exception e) {
                // 한 소스 실패해도 나머지는 계속 수집
                lastError = src[1] + ": " + e.getMessage();
                log.warn("[NewsService] RSS 수집 실패 - source={}, error={}", src[0], e.getMessage());
            }
        }
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24); // 하루가 지난 기사 배제
        collected.removeIf(item -> item.publishedAt() != null && item.publishedAt().isBefore(cutoff));
        cache = deduplicate(collected);
        log.debug("[NewsService] 뉴스 캐시 갱신 완료 - {}건", cache.size());

        // 한 소스라도 성공하면 정상, 전부 실패면 다운
        if (okCount > 0) {
            healthHeartbeat.beat(HEALTH_KEY);
        } else {
            healthHeartbeat.fail(HEALTH_KEY, lastError != null ? lastError : "모든 RSS 소스 실패");
        }
    }

    // 외부에서 캐시된 뉴스 목록 조회
    public List<NewsItem> getNews() {
        return cache;
    }

    // 단일 RSS 소스 파싱
    private List<NewsItem> fetch(String url, String source, String category) throws Exception {
        SyndFeedInput input = new SyndFeedInput();
        // HttpURLConnection으로 직접 연결 — User-Agent 미설정 시 일부 서버가 400 반환
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; RSS reader)");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        // try-with-resources: XmlReader가 자동으로 닫혀 연결 자원 누수 방지
        SyndFeed feed;
        try (XmlReader reader = new XmlReader(conn.getInputStream())) {
            feed = input.build(reader);
        } finally {
            conn.disconnect();
        }

        List<NewsItem> items = new ArrayList<>();
        for (SyndEntry entry : feed.getEntries()) {
            String title = entry.getTitle();
            String link  = entry.getLink();
            if (title == null || title.isBlank() || link == null || link.isBlank()) continue;

            // RSS pubDate → LocalDateTime 변환
            LocalDateTime publishedAt = null;
            if (entry.getPublishedDate() != null) {
                publishedAt = entry.getPublishedDate()
                        .toInstant()
                        .atZone(ZoneId.of("Asia/Seoul"))
                        .toLocalDateTime();
            }
            items.add(new NewsItem(title.strip(), link.strip(), source, category, publishedAt));
            // 소스당 최대 10건 — 중복 제거 후 화면 표시 기준으로 충분한 수량
            if (items.size() >= 10) break;
        }
        return items;
    }

    // 중복 제거
    // 1차: link URL 완전 일치
    // 2차: 제목 앞 20자 정규화(공백·특수문자 제거) 일치 → 같은 기사를 다른 출처가 다른 URL로 올린 경우 처리
    private List<NewsItem> deduplicate(List<NewsItem> items) {
        Set<String> seenLinks  = new HashSet<>();
        Set<String> seenTitles = new HashSet<>();
        List<NewsItem> result  = new ArrayList<>();

        for (NewsItem item : items) {
            if (!seenLinks.add(item.link())) continue;

            String titleKey = item.title()
                    .replaceAll("[\\s\\p{Punct}]", "")  // 공백·구두점 제거
                    .toLowerCase();
            titleKey = titleKey.substring(0, Math.min(20, titleKey.length()));

            if (!seenTitles.add(titleKey)) continue;

            result.add(item);
        }
        // publishedAt 최신순 정렬 — null은 맨 뒤로
        result.sort(Comparator.comparing(NewsItem::publishedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return Collections.unmodifiableList(result);
    }
}