package com.chs.springboot.domain.chatbot.controller;

import com.chs.springboot.domain.chatbot.dto.ReindexJob;
import com.chs.springboot.domain.chatbot.service.ChatbotLogService;
import com.chs.springboot.domain.chatbot.service.CodebaseIndexingService;
import com.chs.springboot.global.auth.jwt.JwtTokenProvider;
import com.chs.springboot.global.auth.service.AuthService;
import com.chs.springboot.global.config.SecurityConfig;
import com.chs.springboot.global.monitor.service.MetricCollectorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatbotAdminController.class)
@Import(SecurityConfig.class)
class ChatbotAdminControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CodebaseIndexingService indexingService;

    @MockBean
    private ChatbotLogService chatbotLogService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private AuthService authService;

    @MockBean
    private MetricCollectorService metricCollectorService;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Test
    @DisplayName("POST /api/admin/chatbot/reindex -> 인증 없으면 차단")
    void startReindex_deniesAnonymous() throws Exception {
        mockMvc.perform(post("/api/admin/chatbot/reindex"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(indexingService);
    }

    @Test
    @WithMockUser(authorities = "ADMIN_ACCESS")
    @DisplayName("POST /api/admin/chatbot/reindex -> ADMIN_ACCESS 권한이면 허용")
    void startReindex_allowsAdminAccess() throws Exception {
        when(indexingService.startReindex()).thenReturn(new ReindexJob("job-1"));

        mockMvc.perform(post("/api/admin/chatbot/reindex"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-1"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }
}
