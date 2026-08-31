package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKline;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** agg_trade_1m_temp 전용 add-only 저장기. 기존 운영 테이블은 절대 건드리지 않는다. */
@Component
public class BinanceKlineTempWriter {

    private static final String INSERT_SQL = """
            INSERT IGNORE INTO agg_trade_1m_temp
                (symbol, market_type, candle_time_ms, close_time_ms,
                 open_price, high_price, low_price, close_price,
                 volume, quote_volume, trade_count,
                 taker_buy_base_volume, taker_buy_quote_volume)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate batchJdbcTemplate;

    public BinanceKlineTempWriter(JdbcTemplate batchJdbcTemplate) {
        this.batchJdbcTemplate = batchJdbcTemplate;
    }

    public int insertIgnore(String symbol, String marketType, List<BinanceKline> klines) {
        if (klines.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = new ArrayList<>(klines.size());
        for (BinanceKline kline : klines) {
            batch.add(new Object[]{
                    symbol,
                    marketType,
                    kline.openTimeMs(),
                    kline.closeTimeMs(),
                    kline.openPrice(),
                    kline.highPrice(),
                    kline.lowPrice(),
                    kline.closePrice(),
                    kline.volume(),
                    kline.quoteVolume(),
                    kline.tradeCount(),
                    kline.takerBuyBaseVolume(),
                    kline.takerBuyQuoteVolume()
            });
        }
        int[] results = batchJdbcTemplate.batchUpdate(INSERT_SQL, batch);
        int inserted = 0;
        for (int result : results) {
            if (result > 0) {
                inserted += result;
            }
        }
        return inserted;
    }
}
