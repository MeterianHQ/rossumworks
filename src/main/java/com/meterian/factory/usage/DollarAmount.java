package com.meterian.factory.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Formats an amount in US dollars, for example $4.12. */
public final class DollarAmount {

    private DollarAmount() {
    }

    public static String format(BigDecimal amount) {
        return "$" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
