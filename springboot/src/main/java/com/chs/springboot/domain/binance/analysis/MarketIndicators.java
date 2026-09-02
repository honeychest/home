package com.chs.springboot.domain.binance.analysis;

import com.chs.springboot.domain.binance.service.MacdCalculator;
import com.chs.springboot.domain.binance.service.RsiCalculator;
import com.chs.springboot.domain.binance.service.SupertrendCalculator;

public record MarketIndicators(
        RsiCalculator.History rsi,
        MacdCalculator.History macd,
        SupertrendCalculator.History supertrend
) {
    public boolean hasLatestValues() {
        return rsi != null && rsi.latest() != null
                && macd != null && macd.latest() != null
                && supertrend != null && supertrend.latest() != null;
    }
}
