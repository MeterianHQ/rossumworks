package com.meterian.factory.definition;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DefinitionVersionTextTest {

    @Test
    void shortensACommit() {
        assertThat(DefinitionVersionText.shorten("3f2a9c1d8e")).isEqualTo("3f2a9c1");
    }

    @Test
    void keepsThePrefixOfALocalVersion() {
        assertThat(DefinitionVersionText.shorten("local:8be41d0aa77")).isEqualTo("local:8be41d0");
    }

    @Test
    void keepsAShortText() {
        assertThat(DefinitionVersionText.shorten("3f2a9")).isEqualTo("3f2a9");
    }
}
