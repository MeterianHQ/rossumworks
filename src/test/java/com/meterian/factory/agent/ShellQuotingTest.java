package com.meterian.factory.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ShellQuotingTest {

    @Test
    void quotePutsTheValueInSingleQuotes() {
        assertThat(ShellQuoting.quote("Fix #7")).isEqualTo("'Fix #7'");
    }

    @Test
    void quoteEscapesASingleQuote() {
        assertThat(ShellQuoting.quote("it's")).isEqualTo("'it'\\''s'");
    }

    @Test
    void theShellReadsTheQuotedValuesBackUnchanged() throws Exception {
        List<String> values = List.of("it's a 'test'", "$HOME", "a b", "`date`", "\"x\"", "!", "", "a\\b", "*", "first line\nsecond line");

        String output = runInShell("printf '%s\\n' " + ShellQuoting.join(values));

        assertThat(output).isEqualTo(String.join("\n", values) + "\n");
    }

    private String runInShell(String command) throws Exception {
        Process process = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor(10, TimeUnit.SECONDS);
        return output;
    }
}
