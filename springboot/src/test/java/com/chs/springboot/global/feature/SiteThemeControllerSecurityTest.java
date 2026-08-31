package com.chs.springboot.global.feature;

import com.chs.springboot.global.auth.jwt.JwtTokenProvider;
import com.chs.springboot.global.auth.service.AuthService;
import com.chs.springboot.global.config.SecurityConfig;
import com.chs.springboot.global.monitor.service.MetricCollectorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SiteThemeController.class)
@Import(SecurityConfig.class)
class SiteThemeControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SiteThemeService siteThemeService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private AuthService authService;

    @MockBean
    private MetricCollectorService metricCollectorService;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void patchIsForbiddenWithoutAdminAuthority() throws Exception {
        mockMvc.perform(patch("/api/admin/site-theme")
                        .contentType(APPLICATION_JSON)
                        .content("{\"analysis\":\"dark\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(siteThemeService);
    }

    @Test
    @WithMockUser(authorities = "ADMIN_ACCESS")
    void patchRejectsInvalidTheme() throws Exception {
        doThrow(new IllegalArgumentException("unsupported theme"))
                .when(siteThemeService).setTheme("analysis", "unsupported");

        mockMvc.perform(patch("/api/admin/site-theme")
                        .contentType(APPLICATION_JSON)
                        .content("{\"analysis\":\"unsupported\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "ADMIN_ACCESS")
    void patchRejectsInvalidPage() throws Exception {
        doThrow(new IllegalArgumentException("unsupported page"))
                .when(siteThemeService).setTheme("unknown", "dark");

        mockMvc.perform(patch("/api/admin/site-theme")
                        .contentType(APPLICATION_JSON)
                        .content("{\"unknown\":\"dark\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getRemainsPublic() throws Exception {
        when(siteThemeService.getAll()).thenReturn(Map.of("analysis", "dark"));

        mockMvc.perform(get("/api/site-theme"))
                .andExpect(status().isOk());
    }
}
