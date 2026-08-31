-- Binance REST kline 원본 1분봉을 임시 검증용으로 보관한다.
-- 기존 agg_trade_1m 및 agg_trade_5m 롤업 파이프라인과 연결하지 않는다.
CREATE TABLE agg_trade_1m_temp (
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
    UNIQUE KEY uq_agg_trade_1m_temp (symbol, market_type, candle_time_ms),
    KEY idx_agg_trade_1m_temp_candle_time (candle_time_ms)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
