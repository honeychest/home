package com.chs.springboot.global.admin.test.binance;

import com.chs.springboot.domain.binance.model.BinanceAnalysisResponse;

/** 비리더 인스턴스가 실제 리더로 분석 요청을 내부 전달한 결과. */
public sealed interface AnalysisForwardOutcome {

    /** 전달에 성공해 리더의 응답을 그대로 받았다. setCookieHeader는 리더가 accessToken을 갱신했을 때만 값이 있다. */
    record Forwarded(BinanceAnalysisResponse response, String setCookieHeader) implements AnalysisForwardOutcome {
    }

    /** 전달 대상이 없다(리더 이름 불명, 자기 자신, peer 주소 미설정, 이미 한 번 전달된 요청 등). */
    record NotEligible() implements AnalysisForwardOutcome {
    }

    /** 전달을 시도했으나 실패했다(연결 실패, 타임아웃, 5xx 등). */
    record Failed() implements AnalysisForwardOutcome {
    }
}
