package com.chs.springboot.global.feature;

import com.chs.springboot.global.config.service.AppConfigService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SiteThemeServiceTest {

    private final AppConfigService appConfigService = mock(AppConfigService.class);
    private final SiteThemeService service = new SiteThemeService(appConfigService);

    @Test
    void getUsesSafeDefaultForInvalidStoredTheme() {
        when(appConfigService.get("theme:analysis")).thenReturn("unsupported");

        assertThat(service.getTheme("analysis", "dark")).isEqualTo("dark");
    }

    @Test
    void getAcceptsTealAndHarborThemes() {
        when(appConfigService.get("theme:analysis")).thenReturn(" teal ");
        when(appConfigService.get("theme:binance")).thenReturn("HARBOR");

        assertThat(service.getTheme("analysis", "dark")).isEqualTo("teal");
        assertThat(service.getTheme("binance", "dark")).isEqualTo("harbor");
    }

    @Test
    void getUsesPageAllowlistAndSafeDefaultForInvalidPage() {
        assertThat(service.getTheme("unknown", "black")).isEqualTo("dark");
        verifyNoInteractions(appConfigService);
    }

    @Test
    void setNormalizesAllowedThemeAndDelegatesToAppConfig() {
        service.setTheme("signal", " DARK ");
        service.setTheme("analysis", " TEAL ");
        service.setTheme("binance", "Harbor");

        verify(appConfigService).set("theme:signal", "dark");
        verify(appConfigService).set("theme:analysis", "teal");
        verify(appConfigService).set("theme:binance", "harbor");
    }

    @Test
    void setRejectsInvalidThemeAndPage() {
        assertThatThrownBy(() -> service.setTheme("analysis", "unsupported"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.setTheme("unknown", "dark"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(appConfigService);
    }
}
