package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.analysis.BinanceAnalysisTools;
import com.chs.springboot.domain.binance.model.BinanceAnalysisResponse;
import com.chs.springboot.domain.binance.model.BinanceAnalysisStatus;
import com.chs.springboot.domain.binance.model.BinanceAnalysisTurn;
import com.chs.springboot.domain.binance.model.MultiTimeframeMarketSnapshot;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import com.chs.springboot.global.redis.LeadershipChangedEvent;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 자동매매 PoC 시장 분석 — 관리자가 직접 요청할 때만 로컬 LLM을 호출한다(자동 스케줄 없음). */
@Service
public class BinanceAutoTradeAnalysisService {

    private static final int MAX_RECENT_TURNS = 8;
    private static final int MAX_QUESTION_LENGTH = 1_000;
    private static final int MAX_TURN_LENGTH = 2_000;
    private static final int MAX_TOOL_CALLS = 5;

    private final LiveMarketDataService liveMarketDataService;
    private final ChatClient analysisChatClient;
    private final long llmTimeoutMs;
    private final ExecutorService llmExecutor;
    private final AtomicBoolean refreshRunning = new AtomicBoolean(false);

    private volatile BinanceAnalysisResponse lastSuccessfulAnalysis;
    private volatile BinanceAnalysisStatus lastFailureStatus;
    private volatile String lastFailureMessage;
    private volatile long failureGeneration = -1L;
    private final Object leadershipEventLock = new Object();
    private Boolean lastLeaderEvent;
    private long lastLeaderEpoch = Long.MIN_VALUE;
    private String lastLeaderOwnerToken = "";

    @Autowired
    public BinanceAutoTradeAnalysisService(
            LiveMarketDataService liveMarketDataService,
            @Qualifier("binanceAnalysisChatClient") ChatClient analysisChatClient,
            @Value("${binance.analysis.llm-timeout-ms:120000}") long llmTimeoutMs) {
        this(liveMarketDataService, analysisChatClient, llmTimeoutMs,
                Executors.newFixedThreadPool(2, runnable -> {
                    Thread thread = new Thread(runnable, "binance-analysis-llm");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    BinanceAutoTradeAnalysisService(LiveMarketDataService liveMarketDataService, ChatClient analysisChatClient,
                                    long llmTimeoutMs, ExecutorService llmExecutor) {
        if (llmTimeoutMs <= 0) {
            throw new IllegalArgumentException("LLM 타임아웃은 0보다 커야 합니다");
        }
        this.liveMarketDataService = liveMarketDataService;
        this.analysisChatClient = analysisChatClient;
        this.llmTimeoutMs = llmTimeoutMs;
        this.llmExecutor = llmExecutor;
    }

    /** 관리자가 "분석 요청" 버튼을 눌렀을 때만 호출된다 — 자동 주기 실행 없음. */
    public BinanceAnalysisResponse refreshAnalysis() {
        if (!liveMarketDataService.isLeader()) {
            return new BinanceAnalysisResponse(BinanceAnalysisStatus.NOT_LEADER, null, null,
                    null, null, null, lastSuccessAtMs(), "리더 노드에서만 분석 요청을 처리합니다");
        }
        if (!refreshRunning.compareAndSet(false, true)) {
            return new BinanceAnalysisResponse(BinanceAnalysisStatus.ANALYSIS_IN_PROGRESS, null, null,
                    null, null, null, lastSuccessAtMs(), "이미 분석이 진행 중입니다. 잠시 후 다시 시도하세요");
        }
        try {
            MultiTimeframeMarketSnapshot snapshot;
            try {
                snapshot = liveMarketDataService.buildSnapshot();
            } catch (RuntimeException e) {
                long generation = liveMarketDataService.leadershipGeneration();
                recordFailure(BinanceAnalysisStatus.PARTIAL, "시장 스냅샷 생성에 실패했습니다", generation);
                return new BinanceAnalysisResponse(BinanceAnalysisStatus.PARTIAL, null, null,
                        null, null, null, lastSuccessAtMs(), "시장 스냅샷 생성에 실패했습니다");
            }
            return runLlmCall(snapshot, List.of(), automaticPrompt(snapshot), true);
        } finally {
            refreshRunning.set(false);
        }
    }

    public BinanceAnalysisResponse getLatestAnalysis() {
        if (!liveMarketDataService.isLeader()) {
            return new BinanceAnalysisResponse(BinanceAnalysisStatus.NOT_LEADER, null, null,
                    null, null, null, null, "리더 노드에서만 자동 분석 결과를 제공합니다");
        }
        long currentGeneration = liveMarketDataService.leadershipGeneration();
        BinanceAnalysisResponse success = lastSuccessfulAnalysis;
        if (success == null) {
            BinanceAnalysisStatus failureStatus = failureGeneration == currentGeneration ? lastFailureStatus : null;
            String message = failureStatus == null
                    ? "아직 분석 요청 결과가 없습니다" : messageOrDefault("아직 분석 요청 결과가 없습니다");
            return new BinanceAnalysisResponse(BinanceAnalysisStatus.NO_ANALYSIS, failureStatus,
                    null, null, null, null, null, message);
        }
        if (failureGeneration == currentGeneration && lastFailureStatus != null) {
            return new BinanceAnalysisResponse(BinanceAnalysisStatus.STALE, lastFailureStatus,
                    success.answer(), success.asOfMs(), success.generatedAtMs(), success.tookMs(),
                    success.lastSuccessAtMs(), messageOrDefault("마지막 성공 분석 이후 새 분석이 실패했습니다"));
        }
        return success;
    }

    public BinanceAnalysisResponse ask(String question, List<BinanceAnalysisTurn> recentTurns) {
        BinanceAnalysisStatus inputStatus = validateQuestion(question, recentTurns);
        if (inputStatus != null) {
            return new BinanceAnalysisResponse(inputStatus, null, null, null, null, null, null,
                    questionMessage(inputStatus));
        }
        if (!liveMarketDataService.isLeader()) {
            return new BinanceAnalysisResponse(BinanceAnalysisStatus.NOT_LEADER, null, null,
                    null, null, null, null, "리더 노드에서만 시장 분석 질문을 처리합니다");
        }

        MultiTimeframeMarketSnapshot snapshot = liveMarketDataService.buildSnapshot();
        if (snapshot.intervals().stream().noneMatch(interval -> interval.currentPrice() != null)) {
            return new BinanceAnalysisResponse(BinanceAnalysisStatus.BACKFILLING, null, null,
                    snapshot.asOfMs(), null, null, null, "현재 시장 데이터가 아직 준비되지 않았습니다");
        }
        List<Message> history = toMessages(recentTurns);
        return runLlmCall(snapshot, history, questionPrompt(snapshot, question), false);
    }

    /** LLM 호출 + 타임아웃/리더십변경/실패 처리 공통 경로. cacheResult=true면 자동요약 캐시에 반영. */
    private BinanceAnalysisResponse runLlmCall(MultiTimeframeMarketSnapshot snapshot, List<Message> history,
                                               String prompt, boolean cacheResult) {
        long generation = snapshot.leadershipGeneration();
        long startedAtMs = System.currentTimeMillis();
        Future<String> call = llmExecutor.submit(() -> callLlm(snapshot, history, prompt));
        try {
            String answer = call.get(llmTimeoutMs, TimeUnit.MILLISECONDS);
            long tookMs = System.currentTimeMillis() - startedAtMs;
            if (!isCurrentGeneration(generation)) {
                return new BinanceAnalysisResponse(BinanceAnalysisStatus.NOT_LEADER, null, null,
                        snapshot.asOfMs(), null, null, lastSuccessAtMs(),
                        "응답 대기 중 리더십이 변경되어 결과를 폐기했습니다");
            }
            BinanceAnalysisStatus status = snapshot.analysisAvailable()
                    ? BinanceAnalysisStatus.READY : BinanceAnalysisStatus.PARTIAL;
            long generatedAtMs = System.currentTimeMillis();
            Long effectiveLastSuccessAtMs = cacheResult ? Long.valueOf(generatedAtMs) : lastSuccessAtMs();
            BinanceAnalysisResponse response = new BinanceAnalysisResponse(status, null, answer, snapshot.asOfMs(),
                    generatedAtMs, tookMs, effectiveLastSuccessAtMs,
                    snapshot.analysisAvailable() ? "분석 완료" : "일부 인터벌 데이터가 준비되지 않은 상태의 분석입니다");
            if (cacheResult) {
                lastSuccessfulAnalysis = response;
                lastFailureStatus = null;
                lastFailureMessage = null;
                failureGeneration = generation;
            }
            return response;
        } catch (TimeoutException | CancellationException e) {
            call.cancel(true);
            if (cacheResult) {
                recordFailure(BinanceAnalysisStatus.LLM_TIMEOUT, "로컬 LLM 응답 시간이 초과되었습니다", generation);
            }
            return new BinanceAnalysisResponse(BinanceAnalysisStatus.LLM_TIMEOUT, null, null,
                    snapshot.asOfMs(), null, null, lastSuccessAtMs(), "로컬 LLM 응답 시간이 초과되었습니다");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            call.cancel(true);
            if (cacheResult) {
                recordFailure(BinanceAnalysisStatus.LLM_TIMEOUT, "로컬 LLM 호출이 중단되었습니다", generation);
            }
            return new BinanceAnalysisResponse(BinanceAnalysisStatus.LLM_TIMEOUT, null, null,
                    snapshot.asOfMs(), null, null, lastSuccessAtMs(), "로컬 LLM 호출이 중단되었습니다");
        } catch (Exception e) {
            if (cacheResult) {
                recordFailure(BinanceAnalysisStatus.LLM_ERROR, "로컬 LLM 호출에 실패했습니다", generation);
            }
            return new BinanceAnalysisResponse(BinanceAnalysisStatus.LLM_ERROR, null, null,
                    snapshot.asOfMs(), null, null, lastSuccessAtMs(), "로컬 LLM 호출에 실패했습니다");
        }
    }

    private BinanceAnalysisStatus validateQuestion(String question, List<BinanceAnalysisTurn> recentTurns) {
        if (question == null || question.isBlank()) {
            return BinanceAnalysisStatus.EMPTY_QUESTION;
        }
        String normalizedQuestion = normalize(question);
        if (question.trim().length() > MAX_QUESTION_LENGTH) {
            return BinanceAnalysisStatus.QUESTION_TOO_LONG;
        }
        if (recentTurns != null) {
            int fromIndex = Math.max(0, recentTurns.size() - MAX_RECENT_TURNS);
            for (BinanceAnalysisTurn turn : recentTurns.subList(fromIndex, recentTurns.size())) {
                if (turn != null && "user".equalsIgnoreCase(turn.role())
                        && normalizedQuestion.equals(normalize(turn.content()))) {
                    return BinanceAnalysisStatus.DUPLICATE_QUESTION;
                }
            }
        }
        return null;
    }

    private List<Message> toMessages(List<BinanceAnalysisTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        int fromIndex = Math.max(0, turns.size() - MAX_RECENT_TURNS);
        List<BinanceAnalysisTurn> safeTurns = turns.subList(fromIndex, turns.size()).stream()
                .filter(turn -> turn != null && turn.role() != null && turn.content() != null)
                .filter(turn -> turn.content().length() <= MAX_TURN_LENGTH)
                .toList();
        List<Message> messages = new ArrayList<>();
        for (BinanceAnalysisTurn turn : safeTurns) {
            if ("user".equalsIgnoreCase(turn.role())) {
                messages.add(new UserMessage(turn.content()));
            } else if ("assistant".equalsIgnoreCase(turn.role())) {
                messages.add(new AssistantMessage(turn.content()));
            }
        }
        return List.copyOf(messages);
    }

    private String callLlm(MultiTimeframeMarketSnapshot snapshot, List<Message> history, String prompt) {
        String content = analysisChatClient.prompt()
                .messages(history)
                .user(prompt)
                .toolCallbacks(new BinanceAnalysisTools(snapshot).limitedCallbacks(MAX_TOOL_CALLS))
                .call()
                .content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("로컬 LLM 응답 본문이 비어 있습니다");
        }
        return content;
    }

    private String automaticPrompt(MultiTimeframeMarketSnapshot snapshot) {
        return """
                다음 현재 개요를 바탕으로 BTCUSDT 선물 시장의 정보성 분석 요약을 작성하라.
                원본 캔들은 개요에 포함하지 않았으므로 필요할 때만 읽기 전용 툴을 호출하라.
                인터벌별 상태가 READY가 아니면 그 사실과 분석 한계를 함께 말하라.
                매매 주문이나 정확한 손절가는 제시하지 말고, 관찰할 기술적 무효화 후보만 말하라.
                답변의 기준 시각(as-of)은 개요의 asOfMs와 툴 결과의 asOfMs다.

                [현재 개요]
                """ + snapshot.overview();
    }

    private String questionPrompt(MultiTimeframeMarketSnapshot snapshot, String question) {
        return """
                아래 현재 개요와 필요한 읽기 전용 툴 결과를 이용해 관리자 질문에 답하라.
                먼저 답하고, 데이터가 부족하면 없는 정보와 그 영향을 분명히 밝혀라.
                답변 마지막에 이 답변이 개요와 툴 결과의 asOfMs 시각 기준임을 짧게 적어라.

                [현재 개요]
                %s

                [관리자 질문]
                %s
                """.formatted(snapshot.overview(), question.trim());
    }

    private void recordFailure(BinanceAnalysisStatus status, String message, long generation) {
        if (!isCurrentGeneration(generation)) {
            return;
        }
        lastFailureStatus = status;
        lastFailureMessage = message;
        failureGeneration = generation;
    }

    private boolean isCurrentGeneration(long generation) {
        return liveMarketDataService.isLeader()
                && liveMarketDataService.leadershipGeneration() == generation;
    }

    private Long lastSuccessAtMs() {
        BinanceAnalysisResponse response = lastSuccessfulAnalysis;
        return response == null ? null : response.lastSuccessAtMs();
    }

    private String questionMessage(BinanceAnalysisStatus status) {
        return switch (status) {
            case EMPTY_QUESTION -> "질문을 입력하세요";
            case QUESTION_TOO_LONG -> "질문은 1000자 이내로 입력하세요";
            case DUPLICATE_QUESTION -> "최근 대화에 같은 질문이 있어 다시 보내지 않았습니다";
            default -> "질문을 처리할 수 없습니다";
        };
    }

    private String messageOrDefault(String fallback) {
        return lastFailureMessage == null || lastFailureMessage.isBlank() ? fallback : lastFailureMessage;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    @EventListener
    public void onLeadershipChanged(LeadershipChangedEvent event) {
        synchronized (leadershipEventLock) {
            String ownerToken = Objects.requireNonNullElse(event.ownerToken(), "");
            if (lastLeaderEvent != null && event.epoch() < lastLeaderEpoch) {
                return;
            }
            if (lastLeaderEvent != null && lastLeaderEvent == event.leader()
                    && lastLeaderEpoch == event.epoch() && lastLeaderOwnerToken.equals(ownerToken)) {
                return;
            }
            lastLeaderEvent = event.leader();
            lastLeaderEpoch = event.epoch();
            lastLeaderOwnerToken = ownerToken;
            lastSuccessfulAnalysis = null;
            lastFailureStatus = null;
            lastFailureMessage = null;
            failureGeneration = liveMarketDataService.leadershipGeneration();
        }
    }

    @PreDestroy
    public void shutdown() {
        llmExecutor.shutdownNow();
    }
}
