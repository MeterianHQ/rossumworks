package com.meterian.factory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the tests of page.js in Node. A machine without Node skips them, but the build of GitHub always has Node,
 * so the variable CI makes Node necessary.
 */
class PageScriptTest {

    private static final String SCRIPT_TESTS = "src/test/javascript/page.test.js";
    private static final String TIMEOUT_OF_EACH_SCRIPT_TEST = "--test-timeout=10000";
    private static final long TIMEOUT_OF_ALL_SCRIPT_TESTS_SECONDS = 60;

    /** Node writes to a file, so that the wait has a timeout also when Node does not end. */
    @Test
    void theTestsOfThePageScriptPass(@TempDir Path workFolder) throws IOException, InterruptedException {
        assumeTrue(isNodeAvailable() || System.getenv("CI") != null, "Node is not available");
        Path outputFile = workFolder.resolve("node-output.txt");

        Process node = new ProcessBuilder("node", "--test", "--test-reporter=tap", TIMEOUT_OF_EACH_SCRIPT_TEST,
                SCRIPT_TESTS).redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
        boolean ended = node.waitFor(TIMEOUT_OF_ALL_SCRIPT_TESTS_SECONDS, TimeUnit.SECONDS);
        if (!ended) {
            node.destroyForcibly().waitFor();
        }
        String output = Files.readString(outputFile, StandardCharsets.UTF_8);

        assertThat(ended).as("Node did not end in %d seconds. Output:%n%s", TIMEOUT_OF_ALL_SCRIPT_TESTS_SECONDS,
                output).isTrue();
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
