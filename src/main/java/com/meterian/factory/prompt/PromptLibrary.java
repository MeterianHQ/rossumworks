package com.meterian.factory.prompt;

import com.meterian.factory.github.Issue;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Renders the prompts. The texts come from a definition. */
public final class PromptLibrary {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private final PromptTexts texts;
    private final String base;

    public PromptLibrary(PromptTexts texts, String mergeTargetBranch) {
        this.texts = texts;
        this.base = "origin/" + mergeTargetBranch;
    }

    public String implement(Issue issue) {
        return render("implement", texts.implement(), Map.of("issue", IssueFence.fence(issue)));
    }

    /** The prompt of the agent in charge after a live conversation. The conversation has the details. */
    public String continueAfterConversation() {
        return render("continue", texts.continueAfterConversation(), Map.of());
    }

    public String fix(List<Finding> findings) {
        return render("fix", texts.fix(), Map.of("findings", FindingsFence.fence(findings)));
    }

    /** The prompt of the agent for an entry into the coding state with an event without the findings of a fix. */
    public String resume(List<Finding> findings) {
        String template = texts.resume().orElseThrow(
                () -> new IllegalStateException("The definition has no prompt resume"));
        return render("resume", template, Map.of("findings", FindingsFence.fence(findings)));
    }

    /** The names of the reviews of the definition, in its order. */
    public List<String> reviewNames() {
        return List.copyOf(texts.reviews().keySet());
    }

    public String review(String reviewName, Issue issue, String treeHash) {
        String template = texts.reviews().get(reviewName);
        if (template == null) {
            throw new IllegalStateException("The definition has no prompt for the review " + reviewName);
        }
        return render("review " + reviewName, template,
                Map.of("issue", IssueFence.fence(issue), "tree", treeHash, "base", base));
    }

    public String risk(Issue issue) {
        return render("risk", texts.risk(), Map.of("issue", IssueFence.fence(issue), "base", base));
    }

    private String render(String promptName, String template, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null) {
                throw new IllegalStateException(
                        "The prompt " + promptName + " has the placeholder " + matcher.group() + " without a value");
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }
}
