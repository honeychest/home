-- Binance kline REST 5분봉 정식 원본 표. agg_trade_1m_temp(V10, 임시 검증용)를
-- 대체한다 — docs/binance/kline-temp-retire-plan.md 참고. 기존 agg_trade_1m/5m
-- 롤업 파이프라인과는 연결하지 않는다(별도 canonical 경로).
CREATE TABLE binance_kline_5m (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    symbol                   VARCHAR(20)  NOT NULL,
    market_type              VARCHAR(10)  NOT NULL,
    candle_time_ms           BIGINT       NOT NULL,
    close_time_ms            BIGINT       NOT NULL,
    open_price               DECIMAL(30,16) NOT NULL,
    high_price               DECIMAL(30,16) NOT NULL,
    low_price                DECIMAL(30,16) NOT NULL,
    close_price              DECIMAL(30,16) NOT NULL,
    volume                   DECIMAL(30,16) NOT NULL,
    quote_volume             DECIMAL(30,16) NOT NULL,
    trade_count              BIGINT       NOT NULL,
    taker_buy_base_volume    DECIMAL(30,16) NOT NULL,
    taker_buy_quote_volume   DECIMAL(30,16) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_binance_kline_5m (symbol, market_type, candle_time_ms),
    KEY idx_binance_kline_5m_candle_time (candle_time_ms)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
