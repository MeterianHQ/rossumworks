package com.meterian.factory.definition;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LocalVersionTest {

    private static final String HASH = "0123456789abcdef".repeat(4);

    @Test
    void addsThePrefixToTheHash() {
        assertThat(LocalVersion.of(HASH)).isEqualTo("local:" + HASH);
    }

    @Test
    void findsTheHashOfALocalVersion() {
        assertThat(LocalVersion.findHash("local:" + HASH)).contains(HASH);
    }

    @Test
    void findsNoHashInACommit() {
        assertThat(LocalVersion.findHash(HASH)).isEmpty();
    }

    @Test
    void findsNoHashWhenTheHashIsNotValid() {
        assertThat(LocalVersion.findHash("local:../../etc")).isEmpty();
        assertThat(LocalVersion.findHash("local:" + HASH.toUpperCase())).isEmpty();
        assertThat(LocalVersion.findHash("local:" + HASH.substring(1))).isEmpty();
    }

    @Test
    void knowsALocalVersion() {
        assertThat(LocalVersion.isLocal("local:anything")).isTrue();
        assertThat(LocalVersion.isLocal(HASH)).isFalse();
    }

    @Test
    void knowsAValidHash() {
        assertThat(LocalVersion.isValidHash(HASH)).isTrue();
        assertThat(LocalVersion.isValidHash(HASH.toUpperCase())).isFalse();
        assertThat(LocalVersion.isValidHash("../x")).isFalse();
    }
}
