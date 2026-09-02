package com.chs.springboot.domain.binance.model;

import java.util.List;

public record BinanceAnalysisAskRequest(String question, List<BinanceAnalysisTurn> recentTurns) {
}
