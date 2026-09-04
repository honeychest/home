package com.chs.springboot.domain.binance.model;

import java.util.Locale;

/** 바이낸스 거래쌍 입력을 USDT 기준 전체 심볼로 정규화하는 순수 규칙. */
public final class BinanceSymbolNormalizer {

    private static final String QUOTE_ASSET = "USDT";

    private BinanceSymbolNormalizer() {
    }

    public static String normalize(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        return normalized.endsWith(QUOTE_ASSET) ? normalized : normalized + QUOTE_ASSET;
    }
}
