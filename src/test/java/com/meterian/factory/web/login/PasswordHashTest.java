package com.meterian.factory.web.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PasswordHashTest {

    private static final int FAST_ITERATIONS = 1000;

    private char[] password(String text) {
        return text.toCharArray();
    }

    @Test
    void aHashMatchesItsPasswordOnly() {
        PasswordHash hash = PasswordHash.create(password("correct horse battery"), FAST_ITERATIONS);

        assertThat(hash.matches(password("correct horse battery"))).isTrue();
        assertThat(hash.matches(password("wrong horse battery"))).isFalse();
    }

    @Test
    void matchesClearsThePasswordArray() {
        PasswordHash hash = PasswordHash.create(password("correct horse battery"), FAST_ITERATIONS);
        char[] attempt = password("correct horse battery");

        hash.matches(attempt);

        assertThat(attempt).containsOnly('\0');
    }

    @Test
    void createClearsThePasswordArray() {
        char[] secret = password("correct horse battery");

        PasswordHash.create(secret, FAST_ITERATIONS);

        assertThat(secret).containsOnly('\0');
    }

    @Test
    void theTextHasTheFormatAndTheDefaultIterations() {
        PasswordHash hash = PasswordHash.create(password("correct horse battery"));

        assertThat(hash.format()).startsWith("pbkdf2-sha256:600000:");
        assertThat(hash.format().split(":")).hasSize(4);
        assertThat(hash.iterations()).isEqualTo(600_000);
    }

    @Test
    void twoHashesOfOnePasswordDiffer() {
        PasswordHash first = PasswordHash.create(password("correct horse battery"), FAST_ITERATIONS);
        PasswordHash second = PasswordHash.create(password("correct horse battery"), FAST_ITERATIONS);

        assertThat(first.format()).isNotEqualTo(second.format());
    }

    @Test
    void parseReadsBackTheSameHash() {
        PasswordHash hash = PasswordHash.create(password("correct horse battery"), FAST_ITERATIONS);

        PasswordHash parsed = PasswordHash.parse(hash.format());

        assertThat(parsed.format()).isEqualTo(hash.format());
        assertThat(parsed.iterations()).isEqualTo(FAST_ITERATIONS);
        assertThat(parsed.matches(password("correct horse battery"))).isTrue();
    }

    @Test
    void parseRefusesAnotherFormat() {
        for (String text : new String[] {"", "plain", "md5:1000:AAAA:BBBB", "pbkdf2-sha256:many:AAAA:BBBB",
                "pbkdf2-sha256:1000:AAAA", "pbkdf2-sha256:1000:!!!:BBBB"}) {
            assertThatThrownBy(() -> PasswordHash.parse(text)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
