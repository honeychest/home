package com.chs.springboot.domain.binance.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinanceSymbolNormalizerTest {

    @Test
    void normalizeReturnsUppercaseUsdtSymbol() {
        assertEquals("BTCUSDT", BinanceSymbolNormalizer.normalize(" btc "));
        assertEquals("BTCUSDT", BinanceSymbolNormalizer.normalize("BTCUSDT"));
    }

    @Test
    void normalizeRejectsBlankSymbol() {
        assertThrows(IllegalArgumentException.class, () -> BinanceSymbolNormalizer.normalize(" "));
    }
}
