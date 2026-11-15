package com.meterian.factory.definition;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ColumnNamesTest {

    @Test
    void writesTheDisplayNameWithACapitalLetterAndSpaces() {
        assertThat(ColumnNames.displayName("waits-for-you")).isEqualTo("Waits for you");
        assertThat(ColumnNames.displayName("done")).isEqualTo("Done");
    }

    @Test
    void acceptsOnlySmallLettersDigitsAndHyphens() {
        assertThat(ColumnNames.isValid("in-review")).isTrue();
        assertThat(ColumnNames.isValid("stage2")).isTrue();
        assertThat(ColumnNames.isValid("In Review")).isFalse();
        assertThat(ColumnNames.isValid("-review")).isFalse();
        assertThat(ColumnNames.isValid("in--review")).isFalse();
        assertThat(ColumnNames.isValid("")).isFalse();
    }
}
