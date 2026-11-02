package com.meterian.factory.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class WebPackageTest {

    private static final Path WEB_SOURCES = Path.of("src/main/java/com/meterian/factory/web");

    @Test
    void noWebClassUsesTheGitHubClient() throws IOException {
        List<Path> sources = findJavaSources();

        assertThat(sources).isNotEmpty();
        assertThat(sources).filteredOn(WebPackageTest::mentionsTheGitHubClient).isEmpty();
    }

    private static List<Path> findJavaSources() throws IOException {
        try (Stream<Path> files = Files.walk(WEB_SOURCES)) {
            return files.filter(file -> file.toString().endsWith(".java")).toList();
        }
    }

    private static boolean mentionsTheGitHubClient(Path source) {
        try {
            return Files.readString(source).contains("GitHubClient");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
