package com.meterian.factory.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.github.Issue;
import com.meterian.factory.state.ReviewKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PromptLibraryTest {

    private static final Issue ISSUE = new Issue(7, "node-7", "The title", "fix the parser {{base}}", Set.of());

    @TempDir
    Path temporaryDirectory;

    @Test
    void overrideDirectoryReplacesABuiltInPrompt() throws IOException {
        Files.writeString(temporaryDirectory.resolve("risk.md"), "X");
        PromptLibrary library = new PromptLibrary(Optional.of(temporaryDirectory), "main");

        assertThat(library.risk(ISSUE)).startsWith("X");
    }

    @Test
    void reviewPromptContainsTheTreeHash() {
        PromptLibrary library = new PromptLibrary(Optional.empty(), "main");

        assertThat(library.review(ReviewKind.SECURITY, ISSUE, "abc123def")).contains("abc123def");
    }

    @Test
    void reviewPromptNamesTheBase() {
        PromptLibrary library = new PromptLibrary(Optional.empty(), "develop");

        assertThat(library.review(ReviewKind.CORRECTNESS, ISSUE, "abc123")).contains("git diff origin/develop...HEAD");
    }

    @Test
    void anUnreplacedPlaceholderIsAnError() throws IOException {
        Files.writeString(temporaryDirectory.resolve("risk.md"), "{{unknown}}");
        PromptLibrary library = new PromptLibrary(Optional.of(temporaryDirectory), "main");

        assertThatThrownBy(() -> library.risk(ISSUE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fixPromptListsTheFindings() {
        PromptLibrary library = new PromptLibrary(Optional.empty(), "main");

        assertThat(library.fix(List.of(new Finding("Bad name", "Rename x")))).contains("- Bad name: Rename x");
    }
}
