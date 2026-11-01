package com.meterian.factory.agent;

import java.util.List;
import java.util.Optional;

public record SessionReport(
        boolean success,
        String resultText,
        Optional<String> apiKeySource,
        Optional<ContextUsage> contextUsage,
        List<UsageWindow> usageWindows) {
}
