package com.meterian.factory.agent;

import java.util.List;
import java.util.Optional;

/** The result of one claude session. A stopped session did not fail: the worker asked it to end. */
public record SessionReport(
        boolean success,
        String resultText,
        Optional<String> apiKeySource,
        Optional<ContextUsage> contextUsage,
        List<UsageWindow> usageWindows,
        boolean stopped) {

    public SessionReport(boolean success, String resultText, Optional<String> apiKeySource,
            Optional<ContextUsage> contextUsage, List<UsageWindow> usageWindows) {
        this(success, resultText, apiKeySource, contextUsage, usageWindows, false);
    }
}
