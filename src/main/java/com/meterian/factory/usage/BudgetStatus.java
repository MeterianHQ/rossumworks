package com.meterian.factory.usage;

import java.math.BigDecimal;

/** The cost of today and the daily budget, in US dollars. */
public record BudgetStatus(BigDecimal costToday, BigDecimal dailyBudget) {
}
