package com.meterian.factory.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DollarAmountTest {

    @Test
    void formatsTheAmountWithTwoDecimals() {
        assertThat(DollarAmount.format(new BigDecimal("4.125"))).isEqualTo("$4.13");
        assertThat(DollarAmount.format(new BigDecimal("20"))).isEqualTo("$20.00");
    }
}
