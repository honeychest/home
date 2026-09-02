package com.chs.springboot.domain.binance.model;

public enum BinanceAnalysisStatus {
    READY,
    NOT_LEADER,
    NO_ANALYSIS,
    BACKFILLING,
    PARTIAL,
    STALE,
    LLM_TIMEOUT,
    LLM_ERROR,
    EMPTY_QUESTION,
    QUESTION_TOO_LONG,
    DUPLICATE_QUESTION,
    ANALYSIS_IN_PROGRESS
}
