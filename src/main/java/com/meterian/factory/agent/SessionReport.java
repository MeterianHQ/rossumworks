package com.meterian.factory.agent;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * The result of one claude session. A stopped session did not fail: the worker asked it to end.
 *
 * @param costUsd the cost of the session in US dollars, from the result event
 */
public record SessionReport(
        boolean success,
        String resultText,
        Optional<String> apiKeySource,
        Optional<ContextUsage> contextUsage,
        List<UsageWindow> usageWindows,
        boolean stopped,
        Optional<BigDecimal> costUsd) {

    public SessionReport(boolean success, String resultText, Optional<String> apiKeySource,
            Optional<ContextUsage> contextUsage, List<UsageWindow> usageWindows) {
        this(success, resultText, apiKeySource, contextUsage, usageWindows, false, Optional.empty());
    }

    public SessionReport(boolean success, String resultText, Optional<String> apiKeySource,
            Optional<ContextUsage> contextUsage, List<UsageWindow> usageWindows, boolean stopped) {
        this(success, resultText, apiKeySource, contextUsage, usageWindows, stopped, Optional.empty());
    }
}
