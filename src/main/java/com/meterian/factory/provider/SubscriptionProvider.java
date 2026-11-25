package com.meterian.factory.provider;

import com.meterian.factory.agent.CredentialMismatchException;
import com.meterian.factory.agent.SessionReport;
import com.meterian.factory.configuration.ProviderName;
import com.meterian.factory.usage.CostEvents;
import com.meterian.factory.usage.PhaseGuard;
import com.meterian.factory.usage.UsageTracker;
import java.time.Clock;
import java.util.List;

/** The sessions use the claude login of the owner. Only the subscription sends usage windows. */
public final class SubscriptionProvider implements Provider {

    private static final String SUBSCRIPTION_SOURCE = "none";

    @Override
    public ProviderName name() {
        return ProviderName.SUBSCRIPTION;
    }

    @Override
    public String describe() {
        return "The provider is subscription: the sessions use the claude login of the owner.";
    }

    @Override
    public List<String> phaseArguments() {
        return List.of();
    }

    @Override
    public void verifyCredentialSource(String apiKeySource) throws CredentialMismatchException {
        if (!SUBSCRIPTION_SOURCE.equals(apiKeySource)) {
            throw new CredentialMismatchException("The session uses the credential source " + apiKeySource
                    + ". The provider subscription expects none.");
        }
    }

    /** An ANTHROPIC_AUTH_TOKEN also gives the source none. The usage windows prove the subscription. */
    @Override
    public void verifyFinishedSession(SessionReport report) throws CredentialMismatchException {
        if (report.success() && report.usageWindows().isEmpty()) {
            throw new CredentialMismatchException(
                    "The session sent no usage windows. The provider subscription expects them.");
        }
    }

    @Override
    public boolean hasLiveMode() {
        return true;
    }

    @Override
    public boolean readsUsageWindows() {
        return true;
    }

    @Override
    public PhaseGuard createPhaseGuard(UsageTracker usageTracker, CostEvents costEvents, Clock clock) {
        return usageTracker;
    }
}
