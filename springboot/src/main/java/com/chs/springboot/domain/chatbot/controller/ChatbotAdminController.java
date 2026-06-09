// [AGENT] 역할: 코드베이스 색인 어드민 API | 연관파일: CodebaseIndexingService.java, ReindexJob.java
package com.chs.springboot.domain.chatbot.controller;

import com.chs.springboot.domain.chatbot.dto.ReindexJob;
import com.chs.springboot.domain.chatbot.dto.ReindexStatusResponse;
import com.chs.springboot.domain.chatbot.service.CodebaseIndexingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/chatbot")
public class ChatbotAdminController {

    private final CodebaseIndexingService indexingService;

    public ChatbotAdminController(CodebaseIndexingService indexingService) {
        this.indexingService = indexingService;
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
}
