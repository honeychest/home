package com.chs.springboot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class WeatherController {

    @Value("${weather.api.service-key}")
    private String serviceKey;

    @Value("${weather.api.base-url}")
    private String baseUrl;

    @GetMapping("/weather/all")
    public Map<String, String> getAllWeather() {
        LocalDateTime now = LocalDateTime.now();

        // 💡 초단기예보 핵심: 45분 주기로 데이터가 생성됩니다.
        // 45분 전이면 전달 데이터를 호출해야 안전합니다.
        LocalDateTime target = now.minusMinutes(45);
        String baseDate = target.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String baseTime = target.format(DateTimeFormatter.ofPattern("HH30")); // 예보는 보통 30분 단위

        System.out.println("🚀 초단기예보 호출: " + baseDate + " / " + baseTime);

        Map<String, int[]> locations = new HashMap<>();
        locations.put("서울특별시", new int[]{60, 127});
        locations.put("경기도", new int[]{60, 120});
        locations.put("강원도", new int[]{73, 134});
        locations.put("경상북도", new int[]{89, 90});
        locations.put("경상남도", new int[]{91, 77});
        locations.put("전라북도", new int[]{63, 89});
        locations.put("전라남도", new int[]{51, 67});
        locations.put("충청남도", new int[]{68, 100});
        locations.put("충청북도", new int[]{69, 107});
        locations.put("제주특별자치도", new int[]{52, 38});

        Map<String, String> results = new HashMap<>();
        RestTemplate restTemplate = new RestTemplate();

        locations.forEach((name, coords) -> {
            try {
                URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                        .queryParam("serviceKey", serviceKey)
                        .queryParam("pageNo", "1")
                        .queryParam("numOfRows", "60") // 예보는 여러 개가 나오므로 넉넉히 호출
                        .queryParam("dataType", "JSON")
                        .queryParam("base_date", baseDate)
                        .queryParam("base_time", baseTime)
                        .queryParam("nx", coords[0])
                        .queryParam("ny", coords[1])
                        .build(true).toUri();

                String response = restTemplate.getForObject(uri, String.class);
                results.put(name, extractFcstTmp(response, now.format(DateTimeFormatter.ofPattern("HH00"))));
            } catch (Exception e) {
                results.put(name, "0");
            }
        });
        return results;
    }

    private String fetchWeatherWithRetry(RestTemplate restTemplate, String name, int[] coords, String baseDate, String baseTime) {
        try {
            URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .queryParam("serviceKey", serviceKey)
                    .queryParam("pageNo", "1")
                    .queryParam("numOfRows", "10")
                    .queryParam("dataType", "JSON")
                    .queryParam("base_date", baseDate)
                    .queryParam("base_time", baseTime)
                    .queryParam("nx", coords[0])
                    .queryParam("ny", coords[1])
                    .build(true).toUri();

            String response = restTemplate.getForObject(uri, String.class);

            // 💡 로그 추가: 실제 기상청에서 내려주는 JSON 전체를 확인 (디버깅용)
            // System.out.println("[" + name + "] RAW 응답: " + response);

            String result = extractFcstTmp(response, name);

            // 만약 응답이 "0"(데이터 없음)이라면, 한 시간 전 데이터로 딱 한 번만 더 시도
            if ("0".equals(result)) {
                LocalDateTime prev = LocalDateTime.now().minusHours(1).minusMinutes(45);
                String prevDate = prev.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                String prevTime = prev.format(DateTimeFormatter.ofPattern("HH00"));

                System.out.println("⚠️ " + name + " 데이터 없음 -> 1시간 전으로 재시도: " + prevTime);

                URI retryUri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                        .queryParam("serviceKey", serviceKey)
                        .queryParam("dataType", "JSON")
                        .queryParam("base_date", prevDate)
                        .queryParam("base_time", prevTime)
                        .queryParam("nx", coords[0])
                        .queryParam("ny", coords[1])
                        .build(true).toUri();

                String retryResponse = restTemplate.getForObject(retryUri, String.class);
                result = extractFcstTmp(retryResponse, name);
            }

            return result;
        } catch (Exception e) {
            System.err.println("❌ " + name + " 통신 실패: " + e.getMessage());
            return "0";
        }
    }

    private String extractFcstTmp(String json, String currentHour) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            JsonNode items = root.path("response").path("body").path("items").path("item");

            if (items.isArray()) {
                for (JsonNode item : items) {
                    // 💡 T1H(기온) 카테고리 중, 현재 시간과 가장 가까운 예보시각(fcstTime)을 찾습니다.
                    if ("T1H".equals(item.path("category").asText()) &&
                            currentHour.equals(item.path("fcstTime").asText())) {
                        return item.path("fcstValue").asText();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("파싱 에러: " + e.getMessage());
        }
        return "0";
    }
}