package com.meterian.factory.prompt;

import com.meterian.factory.github.Issue;
import com.meterian.factory.state.ReviewKind;
import java.util.List;
import java.util.Locale;
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

    public String review(ReviewKind kind, Issue issue, String treeHash) {
        String template = texts.reviews().get(kind);
        if (template == null) {
            throw new IllegalStateException("The definition has no prompt for the review " + kind);
        }
        return render("review " + kind.name().toLowerCase(Locale.ROOT), template,
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
