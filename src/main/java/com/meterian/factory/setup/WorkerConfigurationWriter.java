package com.meterian.factory.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Writes the file worker.yaml. It never overwrites a file. */
public final class WorkerConfigurationWriter {

    private WorkerConfigurationWriter() {
    }

    public static void write(WorkerSettings settings) throws IOException {
        Files.writeString(settings.configurationFile(), render(settings), StandardOpenOption.CREATE_NEW);
    }

    public static String render(WorkerSettings settings) {
        StringBuilder text = new StringBuilder();
        text.append("repository: ").append(YamlText.quote(settings.repository().fullName())).append('\n');
        text.append("work-directory: ").append(YamlText.quote(settings.workDirectory().toString())).append('\n');
        text.append("gatekeeper-directory: ")
                .append(YamlText.quote(settings.gatekeeperDirectory().toString())).append('\n');
        appendBuildEnvironment(text, settings);
        appendProvider(text, settings);
        appendTerminalCommand(text, settings);
        appendWebPage(text, settings);
        return text.toString();
    }

    private static void appendBuildEnvironment(StringBuilder text, WorkerSettings settings) {
        if (settings.javaHome().isPresent()) {
            text.append("build:\n  environment:\n    JAVA_HOME: ")
                    .append(YamlText.quote(settings.javaHome().get())).append('\n');
        } else {
            text.append("# build:\n#   environment:\n#     JAVA_HOME: /path/to/jdk\n");
        }
    }

    private static void appendProvider(StringBuilder text, WorkerSettings settings) {
        ProviderChoice provider = settings.provider();
        if (provider.dailyBudgetUsd().isEmpty()) {
            text.append("# claude:\n#   provider: anthropic-api-key   # needs ANTHROPIC_API_KEY\n")
                    .append("# limits:\n#   daily-budget-usd: 20\n");
            return;
        }
        text.append("claude:\n  provider: ").append(provider.name().configurationValue()).append('\n');
        text.append("limits:\n  daily-budget-usd: ").append(provider.dailyBudgetUsd().get().toPlainString())
                .append('\n');
    }

    private static void appendTerminalCommand(StringBuilder text, WorkerSettings settings) {
        if (settings.terminalCommand().isPresent()) {
            text.append("live:\n  terminal-command: ")
                    .append(formatList(settings.terminalCommand().get())).append('\n');
        } else {
            text.append("# live:\n#   terminal-command: [gnome-terminal, --]\n");
        }
    }

    private static void appendWebPage(StringBuilder text, WorkerSettings settings) {
        if (settings.web().isPresent()) {
            WebPage page = settings.web().get();
            text.append("web:\n");
            text.append("  address: ").append(YamlText.quote(page.address())).append('\n');
            text.append("  port: ").append(page.port()).append('\n');
            appendPasswordHash(text, page.passwordHash());
        } else {
            text.append("# web:\n#   address: 127.0.0.1\n#   port: 8090\n#   password-hash: \"...\"\n");
        }
    }

    private static void appendPasswordHash(StringBuilder text, Optional<String> passwordHash) {
        if (passwordHash.isPresent()) {
            text.append("  password-hash: ").append(YamlText.quote(passwordHash.get())).append('\n');
        } else {
            text.append("  # No password-hash: the page has no login.\n");
        }
    }

    private static String formatList(List<String> values) {
        return values.stream().map(YamlText::quote).collect(Collectors.joining(", ", "[", "]"));
    }
}
