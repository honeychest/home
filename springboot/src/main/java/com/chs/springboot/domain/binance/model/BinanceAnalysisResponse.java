package com.chs.springboot.domain.binance.model;

/** 자동 요약과 관리자 질문 응답의 공통 무저장 API 계약. */
public record BinanceAnalysisResponse(
        BinanceAnalysisStatus status,
        BinanceAnalysisStatus failureStatus,
        String answer,
        Long asOfMs,
        Long generatedAtMs,
        Long tookMs,
        Long lastSuccessAtMs,
        String message
) {
}
