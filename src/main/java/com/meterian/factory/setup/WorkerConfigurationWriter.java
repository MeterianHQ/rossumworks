package com.meterian.factory.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
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
            text.append("  password-hash: ").append(YamlText.quote(page.passwordHash())).append('\n');
        } else {
            text.append("# web:\n#   address: 127.0.0.1\n#   port: 8090\n#   password-hash: \"...\"\n");
        }
    }

    private static String formatList(List<String> values) {
        return values.stream().map(YamlText::quote).collect(Collectors.joining(", ", "[", "]"));
    }
}
