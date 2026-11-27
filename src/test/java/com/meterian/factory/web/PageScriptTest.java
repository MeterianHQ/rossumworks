package com.meterian.factory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Runs the tests of page.js in Node. A machine without Node skips them, but the build of GitHub always has Node,
 * so the variable CI makes Node necessary.
 */
class PageScriptTest {

    private static final String SCRIPT_TESTS = "src/test/javascript/page.test.js";

    @Test
    void theTestsOfThePageScriptPass() throws IOException, InterruptedException {
        assumeTrue(isNodeAvailable() || System.getenv("CI") != null, "Node is not available");

        Process node = new ProcessBuilder("node", "--test", "--test-reporter=tap", SCRIPT_TESTS).redirectErrorStream(true).start();
        String output = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(node.waitFor(1, TimeUnit.MINUTES)).isTrue();
        assertThat(node.exitValue()).as(output).isZero();
        assertThat(output).contains("# fail 0").doesNotContain("# pass 0");
    }

    private static boolean isNodeAvailable() throws InterruptedException {
        try {
            Process version = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            version.getInputStream().readAllBytes();
            return version.waitFor(10, TimeUnit.SECONDS) && version.exitValue() == 0;
        } catch (IOException exception) {
            return false;
        }
    }
}
