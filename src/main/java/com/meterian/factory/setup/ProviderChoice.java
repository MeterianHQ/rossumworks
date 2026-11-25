package com.meterian.factory.setup;

import com.meterian.factory.configuration.ProviderName;
import java.math.BigDecimal;
import java.util.Optional;

/** The provider that create writes to worker.yaml. The API key provider always has a budget. */
public record ProviderChoice(ProviderName name, Optional<BigDecimal> dailyBudgetUsd) {

    private static final BigDecimal MINIMUM_BUDGET = new BigDecimal("0.01");
    private static final BigDecimal MAXIMUM_BUDGET = new BigDecimal("1000000");

    public static ProviderChoice subscription() {
        return new ProviderChoice(ProviderName.SUBSCRIPTION, Optional.empty());
    }

    /** Returns the budget, or empty when the text is not a number from 0.01 to 1000000. */
    public static Optional<BigDecimal> parseDailyBudget(String text) {
        try {
            BigDecimal budget = new BigDecimal(text.strip());
            return isInRange(budget) ? Optional.of(budget) : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    /** The range keeps a value that run reads as a YAML double, for example not 1e400 or 1e-400. */
    private static boolean isInRange(BigDecimal budget) {
        return budget.compareTo(MINIMUM_BUDGET) >= 0 && budget.compareTo(MAXIMUM_BUDGET) <= 0;
    }
}
