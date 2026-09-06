package com.chs.springboot.global.monitor;

public final class SourceEnvNormalizer {

    private SourceEnvNormalizer() {
    }

    public static String normalize(String sourceEnv) {
        if (sourceEnv == null || sourceEnv.isBlank()) {
            return "local";
        }
        String normalized = sourceEnv.toLowerCase().trim();
        if (normalized.contains("prod")) {
            return "prod";
        }
        if (normalized.contains("local")) {
            return "local";
        }
        return normalized.split(",")[0].trim();
    }
}
