package com.meterian.factory.prompt;

import com.meterian.factory.github.Issue;
import com.meterian.factory.state.ReviewKind;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Renders the prompts. A file in the override directory replaces the built-in file of the same name. */
public final class PromptLibrary {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private final Optional<Path> overrideDirectory;
    private final String base;

    public PromptLibrary(Optional<Path> overrideDirectory, String mergeTargetBranch) {
        this.overrideDirectory = overrideDirectory;
        this.base = "origin/" + mergeTargetBranch;
    }

    public String implement(Issue issue) {
        return render("implement.md", Map.of("issue", IssueFence.fence(issue)));
    }

    /** The prompt of the agent in charge after a live conversation. The conversation has the details. */
    public String continueAfterConversation() {
        return render("continue.md", Map.of());
    }

    public String fix(List<Finding> findings) {
        return render("fix.md", Map.of("findings", FindingsFence.fence(findings)));
    }

    public String review(ReviewKind kind, Issue issue, String treeHash) {
        String fileName = "review-" + kind.name().toLowerCase(Locale.ROOT) + ".md";
        return render(fileName, Map.of("issue", IssueFence.fence(issue), "tree", treeHash, "base", base));
    }

    public String risk(Issue issue) {
        return render("risk.md", Map.of("issue", IssueFence.fence(issue), "base", base));
    }

    private String render(String fileName, Map<String, String> values) {
        String template = loadTemplate(fileName);
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null) {
                throw new IllegalStateException(
                        "The prompt " + fileName + " has the placeholder " + matcher.group() + " without a value");
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private String loadTemplate(String fileName) {
        try {
            if (overrideDirectory.isPresent() && Files.exists(overrideDirectory.get().resolve(fileName))) {
                return Files.readString(overrideDirectory.get().resolve(fileName), StandardCharsets.UTF_8);
            }
            try (InputStream stream = PromptLibrary.class.getResourceAsStream("/prompts/" + fileName)) {
                if (stream == null) {
                    throw new IllegalStateException("The built-in prompt " + fileName + " does not exist");
                }
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read the prompt " + fileName, exception);
        }
    }
}
