package com.chs.springboot.global.feature;

import com.chs.springboot.global.config.service.AppConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class FeatureFlagService {

    public static final String KEY_TRADE_THRESHOLD_EDIT = "feature:trade:threshold-edit";
    public static final String KEY_MONITOR_ALLOWED_IP_MANAGE = "feature:monitor:allowed-ip-manage";

    private final AppConfigService appConfigService;

    public Map<String, Boolean> getAll() {
        return Map.of(
                "tradeThresholdEdit", isEnabled(KEY_TRADE_THRESHOLD_EDIT, true),
                "monitorAllowedIpManage", isEnabled(KEY_MONITOR_ALLOWED_IP_MANAGE, false)
        );
    }

    public boolean isTradeThresholdEditEnabled() {
        return isEnabled(KEY_TRADE_THRESHOLD_EDIT, true);
    }

    public boolean isMonitorAllowedIpManageEnabled() {
        return isEnabled(KEY_MONITOR_ALLOWED_IP_MANAGE, false);
    }

    public void setTradeThresholdEdit(Boolean enabled) {
        if (enabled == null) return;
        set(KEY_TRADE_THRESHOLD_EDIT, enabled);
    }

    public void setMonitorAllowedIpManage(Boolean enabled) {
        if (enabled == null) return;
        set(KEY_MONITOR_ALLOWED_IP_MANAGE, enabled);
    }

    private boolean isEnabled(String key, boolean defaultValue) {
        try {
            String v = appConfigService.get(key);
            if (v == null) return defaultValue;
            return "ON".equalsIgnoreCase(v.trim())
                    || "TRUE".equalsIgnoreCase(v.trim())
                    || "1".equals(v.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private void set(String key, boolean enabled) {
        appConfigService.set(key, enabled ? "ON" : "OFF");
    }
}

