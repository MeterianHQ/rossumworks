package com.meterian.factory.setup;

import com.meterian.factory.configuration.ProviderName;
import java.math.BigDecimal;
import java.util.Optional;

/** The provider that create writes to worker.yaml. The API key provider always has a budget. */
public record ProviderChoice(ProviderName name, Optional<BigDecimal> dailyBudgetUsd) {

    public static ProviderChoice subscription() {
        return new ProviderChoice(ProviderName.SUBSCRIPTION, Optional.empty());
    }

    /** Returns the budget, or empty when the text is not a number more than 0. */
    public static Optional<BigDecimal> parseDailyBudget(String text) {
        try {
            BigDecimal budget = new BigDecimal(text.strip());
            return budget.signum() > 0 ? Optional.of(budget) : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }
}
