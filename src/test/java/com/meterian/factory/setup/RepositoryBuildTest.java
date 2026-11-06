package com.meterian.factory.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class RepositoryBuildTest {

    @Test
    void allowsTheFirstWordOfACustomCommand() {
        RepositoryBuild build = RepositoryBuild.custom("make test", false);

        assertThat(build.command()).isEqualTo("make test");
        assertThat(build.allowedTools()).isEqualTo(List.of("Bash(make *)"));
    }

    @Test
    void splitsTheCommandOnAnyWhiteSpace() {
        RepositoryBuild build = RepositoryBuild.custom("  make\ttest", false);

        assertThat(build.allowedTools()).isEqualTo(List.of("Bash(make *)"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "\t"})
    void refusesABlankCommand(String blank) {
        assertThatThrownBy(() -> RepositoryBuild.custom(blank, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
