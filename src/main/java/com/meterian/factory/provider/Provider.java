package com.meterian.factory.provider;

import com.meterian.factory.agent.CredentialMismatchException;
import com.meterian.factory.agent.SessionReport;
import com.meterian.factory.configuration.ProviderName;
import com.meterian.factory.usage.CostEvents;
import com.meterian.factory.usage.PhaseGuard;
import com.meterian.factory.usage.UsageTracker;
import java.io.IOException;
import java.time.Clock;
import java.util.List;

/** The provider of the claude sessions: the subscription or an Anthropic API key. Main creates it once. */
public interface Provider {

    ProviderName name();

    /** The line that run prints at start. It never contains the key. */
    String describe();

    /** The extra arguments of each claude -p phase. They come before the tool lists. */
    List<String> phaseArguments();

    /** Checks the apiKeySource of the init event, as soon as the stream gives it. */
    void verifyCredentialSource(String apiKeySource) throws CredentialMismatchException;

    /** Checks a session after its end. */
    void verifyFinishedSession(SessionReport report) throws CredentialMismatchException;

    boolean hasLiveMode();

    /** True when the sessions send usage windows, so that the usage probe can read them. */
    boolean readsUsageWindows();

    PhaseGuard createPhaseGuard(UsageTracker usageTracker, CostEvents costEvents, Clock clock)
            throws IOException;
}
