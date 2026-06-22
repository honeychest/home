// [AGENT] 역할: pageId(현재 화면 식별자) → 사람이 읽을 페이지 설명/검색보강어 매핑 | 연관파일: ChatbotService.java
// 프론트(FloatingChatbot)가 라우트에서 뽑아 보낸 pageId 를 LLM 프롬프트용 안내문과
// "이 페이지" 류 질문의 검색 보강어로 바꾼다. 모르는 pageId 는 null(무시).
package com.chs.springboot.domain.chatbot.service;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PageContextRegistry {

    /** 한 페이지의 안내 정보. promptHint: LLM 에게 줄 한 줄 설명. searchTerms: "이 페이지" 질문 시 검색질의에 덧붙일 명사들. */
    public record PageInfo(String label, String promptHint, String searchTerms) {
    }

    // pageId 는 프론트 라우트에서 파생(예: "/signal" → "signal"). 설명은 docs/generated/fe-page-*.md 기반.
    private static final Map<String, PageInfo> PAGES = Map.ofEntries(
            Map.entry("signal", new PageInfo(
                    "실시간 신호(Signal) 대시보드",
                    "실시간 매수/매도 에너지, 청산(liquidation), 오픈포지션(OI)을 한 화면에서 보며 단기 매매 신호를 포착하는 대시보드",
                    "signal 실시간 신호 대시보드 에너지 청산 오픈포지션 OI")),
            Map.entry("analysis", new PageInfo(
                    "분석(Analysis) 페이지",
                    "사용자가 설정한 복합 조건(거래량 급증·가격 변동·델타·시간대)에 맞는 과거 거래 패턴을 차트에서 탐색하고 템플릿으로 저장·관리하는 페이지",
                    "analysis 분석 조건 패턴 탐지 템플릿 델타 거래량")),
            Map.entry("binance", new PageInfo(
                    "바이낸스(Binance) 시세 페이지",
                    "Binance 와 Upbit 의 실시간 시세를 티커 카드로 비교해 보여주는 페이지",
                    "binance 바이낸스 업비트 시세 티커 카드")),
            Map.entry("trade", new PageInfo(
                    "트레이드(Trade) 페이지",
                    "실시간 체결 틱 테이블을 보고 조회 사이드 패널로 데이터를 살펴보는 페이지",
                    "trade 트레이드 체결 틱 테이블 조회")),
            Map.entry("logistics", new PageInfo(
                    "물류(Logistics) 시뮬레이션 페이지",
                    "물류 작업(노드/태스크)을 시뮬레이션하고 OMS/TMS 단계를 시각화하는 페이지",
                    "logistics 물류 시뮬레이션 노드 태스크 OMS TMS")),
            Map.entry("monitor", new PageInfo(
                    "모니터(Monitor) 페이지",
                    "시스템 리소스(CPU/RAM/DISK), Docker 컨테이너, Redis, WebSocket 피드 수신 현황을 실시간 모니터링하는 대시보드",
                    "monitor 모니터 시스템 리소스 도커 redis 피드 상태")),
            Map.entry("weather", new PageInfo(
                    "날씨(Weather/Cesium) 페이지",
                    "Cesium 3D 지구본 위에 날씨 정보를 시각화하는 페이지",
                    "weather 날씨 cesium 3d 지구")),
            Map.entry("random", new PageInfo(
                    "랜덤 추첨(Winner) 페이지",
                    "참가자 중 무작위로 당첨자를 뽑는 추첨 페이지",
                    "random winner 추첨 당첨 랜덤")),
            Map.entry("admin", new PageInfo(
                    "관리자(Admin) 페이지",
                    "방문자 로그, 허용 IP, 데이터 수집·롤업·건강성 등을 관리하는 운영자 화면",
                    "admin 관리자 방문자 로그 IP 데이터 수집"))
    );

    /** pageId 에 해당하는 페이지 정보. 없거나 null/빈값이면 null. */
    public PageInfo find(String pageId) {
        if (pageId == null || pageId.isBlank()) {
            return null;
        }
        return PAGES.get(pageId.trim().toLowerCase());
    }
}
