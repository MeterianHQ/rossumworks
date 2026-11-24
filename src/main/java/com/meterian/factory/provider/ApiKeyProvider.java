package com.meterian.factory.provider;

import com.meterian.factory.agent.CredentialMismatchException;
import com.meterian.factory.agent.SessionReport;
import com.meterian.factory.configuration.ProviderName;
import com.meterian.factory.usage.BudgetTracker;
import com.meterian.factory.usage.CostEvents;
import com.meterian.factory.usage.DollarAmount;
import com.meterian.factory.usage.PhaseGuard;
import com.meterian.factory.usage.UsageTracker;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/**
 * The sessions get an Anthropic API key from the apiKeyHelper of a settings file. A daily budget limits the cost.
 * The key is never in an environment, so the commands of the agent do not get it.
 */
public final class ApiKeyProvider implements Provider {

    private static final String HELPER_SOURCE = "apiKeyHelper";

    private final Path settingsFile;
    private final BigDecimal dailyBudgetUsd;

    /** @param settingsFile the settings file of {@link ApiKeyFiles}, which names the helper */
    public ApiKeyProvider(Path settingsFile, BigDecimal dailyBudgetUsd) {
        this.settingsFile = settingsFile;
        this.dailyBudgetUsd = dailyBudgetUsd;
    }

    @Override
    public ProviderName name() {
        return ProviderName.ANTHROPIC_API_KEY;
    }

    @Override
    public String describe() {
        return "The provider is anthropic-api-key: the sessions use an Anthropic API key. The daily budget is "
                + DollarAmount.format(dailyBudgetUsd) + ".";
    }

    @Override
    public List<String> phaseArguments() {
        return List.of("--settings", settingsFile.toString());
    }

    @Override
    public void verifyCredentialSource(String apiKeySource) throws CredentialMismatchException {
        if (!HELPER_SOURCE.equals(apiKeySource)) {
            throw new CredentialMismatchException("The session uses the credential source " + apiKeySource
                    + ". The provider anthropic-api-key expects apiKeyHelper.");
        }
    }

    @Override
    public void verifyFinishedSession(SessionReport report) {
    }

    /** An interactive claude gets "credit balance too low" with a key that works in print mode. */
    @Override
    public boolean hasLiveMode() {
        return false;
    }

    @Override
    public boolean readsUsageWindows() {
        return false;
    }

    @Override
    public PhaseGuard createPhaseGuard(UsageTracker usageTracker, CostEvents costEvents, Clock clock) {
        return new BudgetTracker(dailyBudgetUsd, costEvents, clock);
    }
}
