// [AGENT] 역할: 코드베이스 색인 어드민 API | 연관파일: CodebaseIndexingService.java, ReindexJob.java
package com.chs.springboot.domain.chatbot.controller;

import com.chs.springboot.domain.chatbot.dto.ReindexJob;
import com.chs.springboot.domain.chatbot.dto.ReindexStatusResponse;
import com.chs.springboot.domain.chatbot.dto.ChatbotLogSummaryResponse;
import com.chs.springboot.domain.chatbot.dto.ChatbotLogTurnDetail;
import com.chs.springboot.domain.chatbot.dto.ChatbotLogTurnSummary;
import com.chs.springboot.domain.chatbot.model.ChatbotIssueType;
import com.chs.springboot.domain.chatbot.model.ChatbotTurnStatus;
import com.chs.springboot.domain.chatbot.service.CodebaseIndexingService;
import com.chs.springboot.domain.chatbot.service.ChatbotLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/chatbot")
public class ChatbotAdminController {

    private final CodebaseIndexingService indexingService;
    private final ChatbotLogService chatbotLogService;

    public ChatbotAdminController(CodebaseIndexingService indexingService, ChatbotLogService chatbotLogService) {
        this.indexingService = indexingService;
        this.chatbotLogService = chatbotLogService;
    }

    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> startReindex() {
        try {
            ReindexJob job = indexingService.startReindex();
            return ResponseEntity.accepted().body(Map.of(
                    "jobId", (Object) job.getJobId(),
                    "status", job.getStatus()
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", (Object) e.getMessage()));
        }
    }

    @PostMapping("/reindex/docs")
    public ResponseEntity<Map<String, Object>> startDocsReindex() {
        try {
            ReindexJob job = indexingService.startDocsReindex();
            return ResponseEntity.accepted().body(Map.of(
                    "jobId", (Object) job.getJobId(),
                    "status", job.getStatus()
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", (Object) e.getMessage()));
        }
    }

    @GetMapping("/reindex/{id}")
    public ResponseEntity<ReindexStatusResponse> getReindexStatus(@PathVariable String id) {
        ReindexJob job = indexingService.getJob(id);
        if (job == null) {
            return ResponseEntity.status(404).body(ReindexStatusResponse.notFound(id));
        }
        return ResponseEntity.ok(job.toStatusResponse());
    }

    @GetMapping("/logs/summary")
    public ChatbotLogSummaryResponse getLogSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String pageId,
            @RequestParam(required = false) ChatbotIssueType issueType,
            @RequestParam(required = false) ChatbotTurnStatus status,
            @RequestParam(required = false) Integer minLatencyMs,
            @RequestParam(required = false) String keyword
    ) {
        return chatbotLogService.summarize(from, to, pageId, issueType, status, minLatencyMs, keyword);
    }

    @GetMapping("/logs/turns")
    public Page<ChatbotLogTurnSummary> getLogTurns(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String pageId,
            @RequestParam(required = false) ChatbotIssueType issueType,
            @RequestParam(required = false) ChatbotTurnStatus status,
            @RequestParam(required = false) Integer minLatencyMs,
            @RequestParam(required = false) String keyword,
            Pageable pageable
    ) {
        return chatbotLogService.findTurns(from, to, pageId, issueType, status, minLatencyMs, keyword, pageable);
    }

    @GetMapping("/logs/turns/{id}")
    public ResponseEntity<ChatbotLogTurnDetail> getLogTurnDetail(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(chatbotLogService.getDetail(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
