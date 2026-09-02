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
import org.springframework.scheduling.annotation.Scheduled;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.RejectedExecutionException;

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
    private final ScheduledExecutorService timeoutExecutor;
    private final AtomicBoolean automaticRunning = new AtomicBoolean(false);

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
                }),
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "binance-analysis-timeout");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    BinanceAutoTradeAnalysisService(LiveMarketDataService liveMarketDataService, ChatClient analysisChatClient,
                                    long llmTimeoutMs, ExecutorService llmExecutor) {
        this(liveMarketDataService, analysisChatClient, llmTimeoutMs, llmExecutor,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "binance-analysis-timeout");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    private BinanceAutoTradeAnalysisService(LiveMarketDataService liveMarketDataService,
                                            ChatClient analysisChatClient, long llmTimeoutMs,
                                            ExecutorService llmExecutor, ScheduledExecutorService timeoutExecutor) {
        if (llmTimeoutMs <= 0) {
            throw new IllegalArgumentException("LLM 타임아웃은 0보다 커야 합니다");
        }
        this.liveMarketDataService = liveMarketDataService;
        this.analysisChatClient = analysisChatClient;
        this.llmTimeoutMs = llmTimeoutMs;
        this.llmExecutor = llmExecutor;
        this.timeoutExecutor = timeoutExecutor;
    }

    @Scheduled(fixedDelayString = "${binance.analysis.fixed-delay-ms:300000}")
    public void scheduledAnalysis() {
        if (!liveMarketDataService.isLeader() || !automaticRunning.compareAndSet(false, true)) {
            return;
        }
        MultiTimeframeMarketSnapshot snapshot;
        try {
            snapshot = liveMarketDataService.buildSnapshot();
        } catch (RuntimeException e) {
            recordFailure(BinanceAnalysisStatus.PARTIAL, "시장 스냅샷 생성에 실패했습니다", liveMarketDataService.leadershipGeneration());
            automaticRunning.set(false);
            return;
        }
        long generation = snapshot.leadershipGeneration();
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<ScheduledFuture<?>> timeoutTask = new AtomicReference<>();
        Future<?> llmTask;
        try {
            llmTask = llmExecutor.submit(() -> {
                try {
                    String answer = callLlm(snapshot, List.of(), automaticPrompt(snapshot));
                    if (completed.compareAndSet(false, true)) {
                        completeAutomaticAnalysis(snapshot, generation, answer, null);
                    }
                } catch (Throwable error) {
                    if (completed.compareAndSet(false, true)) {
                        completeAutomaticAnalysis(snapshot, generation, null, error);
                    }
                } finally {
                    automaticRunning.set(false);
                    cancel(timeoutTask.get());
                }
            });
        } catch (RejectedExecutionException e) {
            automaticRunning.set(false);
            recordFailure(BinanceAnalysisStatus.LLM_ERROR, "전용 LLM 실행기를 사용할 수 없습니다", generation);
            return;
        }
        ScheduledFuture<?> scheduledTimeout = timeoutExecutor.schedule(() -> {
            if (completed.compareAndSet(false, true)) {
                llmTask.cancel(true);
                if (isCurrentGeneration(generation)) {
                    recordFailure(BinanceAnalysisStatus.LLM_TIMEOUT,
                            "로컬 LLM 자동 분석 시간이 초과되었습니다", generation);
                }
                automaticRunning.set(false);
            }
        }, llmTimeoutMs, TimeUnit.MILLISECONDS);
        timeoutTask.set(scheduledTimeout);
        if (completed.get()) {
            scheduledTimeout.cancel(false);
        }
    }

    public BinanceAnalysisResponse getLatestAnalysis() {
        if (!liveMarketDataService.isLeader()) {
            return new BinanceAnalysisResponse(BinanceAnalysisStatus.NOT_LEADER, null, null,
                    null, null, null, "리더 노드에서만 자동 분석 결과를 제공합니다");
        }
        BinanceAnalysisResponse success = lastSuccessfulAnalysis;
        if (success == null) {
            return new BinanceAnalysisResponse(BinanceAnalysisStatus.NO_ANALYSIS, lastFailureStatus,
                    null, null, null, null, messageOrDefault("아직 성공한 자동 분석이 없습니다"));
        }
        if (failureGeneration == liveMarketDataService.leadershipGeneration() && lastFailureStatus != null) {
            return new BinanceAnalysisResponse(BinanceAnalysisStatus.STALE, lastFailureStatus,
                    success.answer(), success.asOfMs(), success.generatedAtMs(), success.lastSuccessAtMs(),
                    messageOrDefault("마지막 성공 분석 이후 새 분석이 실패했습니다"));
        }
        return success;
    }

    public BinanceAnalysisResponse ask(String question, List<BinanceAnalysisTurn> recentTurns) {
        BinanceAnalysisStatus inputStatus = validateQuestion(question, recentTurns);
        if (inputStatus != null) {
            return new BinanceAnalysisResponse(inputStatus, null, null, null, null, null,
                    questionMessage(inputStatus));
        }
        if (!liveMarketDataService.isLeader()) {
            return new BinanceAnalysisResponse(BinanceAnalysisStatus.NOT_LEADER, null, null,
                    null, null, null, "리더 노드에서만 시장 분석 질문을 처리합니다");
        }

        MultiTimeframeMarketSnapshot snapshot = liveMarketDataService.buildSnapshot();
        if (snapshot.intervals().stream().noneMatch(interval -> interval.currentPrice() != null)) {
            return new BinanceAnalysisResponse(BinanceAnalysisStatus.BACKFILLING, null, null,
                    snapshot.asOfMs(), null, null, "현재 시장 데이터가 아직 준비되지 않았습니다");
        }
        long generation = snapshot.leadershipGeneration();
        List<Message> history = toMessages(recentTurns);
        Future<String> call = llmExecutor.submit(
                () -> callLlm(snapshot, history, questionPrompt(snapshot, question)));
        try {
            String answer = call.get(llmTimeoutMs, TimeUnit.MILLISECONDS);
            if (!isCurrentGeneration(generation)) {
                return new BinanceAnalysisResponse(BinanceAnalysisStatus.NOT_LEADER, null, null,
                        snapshot.asOfMs(), null, null, "응답 대기 중 리더십이 변경되어 결과를 폐기했습니다");
            }
            BinanceAnalysisStatus status = snapshot.analysisAvailable()
                    ? BinanceAnalysisStatus.READY : BinanceAnalysisStatus.PARTIAL;
            return new BinanceAnalysisResponse(status, null, answer, snapshot.asOfMs(),
                    System.currentTimeMillis(), lastSuccessAtMs(), snapshot.analysisAvailable()
                            ? "스냅샷 기준 시각이 포함된 답변입니다" : "일부 인터벌 데이터가 준비되지 않은 상태의 답변입니다");
        } catch (TimeoutException | CancellationException e) {
            call.cancel(true);
            return new BinanceAnalysisResponse(BinanceAnalysisStatus.LLM_TIMEOUT, null, null,
                    snapshot.asOfMs(), null, lastSuccessAtMs(), "로컬 LLM 응답 시간이 초과되었습니다");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            call.cancel(true);
            return new BinanceAnalysisResponse(BinanceAnalysisStatus.LLM_TIMEOUT, null, null,
                    snapshot.asOfMs(), null, lastSuccessAtMs(), "로컬 LLM 호출이 중단되었습니다");
        } catch (Exception e) {
            return new BinanceAnalysisResponse(BinanceAnalysisStatus.LLM_ERROR, null, null,
                    snapshot.asOfMs(), null, lastSuccessAtMs(), "로컬 LLM 호출에 실패했습니다");
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
                다음 현재 개요를 바탕으로 BTCUSDT 선물 시장의 5분 주기 정보성 분석 요약을 작성하라.
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

    private void completeAutomaticAnalysis(MultiTimeframeMarketSnapshot snapshot, long generation,
                                           String answer, Throwable error) {
        if (!isCurrentGeneration(generation)) {
            return;
        }
        Throwable cause = unwrap(error);
        if (cause != null) {
            BinanceAnalysisStatus failure = cause instanceof TimeoutException
                    ? BinanceAnalysisStatus.LLM_TIMEOUT : BinanceAnalysisStatus.LLM_ERROR;
            recordFailure(failure, cause instanceof TimeoutException
                    ? "로컬 LLM 자동 분석 시간이 초과되었습니다" : "로컬 LLM 자동 분석에 실패했습니다", generation);
            return;
        }
        BinanceAnalysisStatus status = snapshot.analysisAvailable()
                ? BinanceAnalysisStatus.READY : BinanceAnalysisStatus.PARTIAL;
        long generatedAtMs = System.currentTimeMillis();
        lastSuccessfulAnalysis = new BinanceAnalysisResponse(status, null, answer, snapshot.asOfMs(),
                generatedAtMs, generatedAtMs, snapshot.analysisAvailable()
                        ? "자동 분석 완료" : "일부 인터벌 데이터가 준비되지 않은 상태에서 분석 완료");
        lastFailureStatus = null;
        lastFailureMessage = null;
        failureGeneration = generation;
    }

    private void recordFailure(BinanceAnalysisStatus status, String message, long generation) {
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

    private void cancel(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private Throwable unwrap(Throwable error) {
        if (error == null) {
            return null;
        }
        if (error.getCause() != null && (error instanceof java.util.concurrent.CompletionException
                || error instanceof java.util.concurrent.ExecutionException)) {
            return error.getCause();
        }
        return error;
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
        timeoutExecutor.shutdownNow();
    }
}
