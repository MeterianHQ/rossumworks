package com.meterian.factory.setup;

import java.util.Map;
import java.util.Optional;

/** The summary that the owner reads before the confirmation. */
final class SetupSummary {

    private SetupSummary() {
    }

    /** The summary when the command writes worker.yaml and opens the PR factory/setup. */
    static String describe(WorkerSettings settings, RepositoryBuild build, Map<String, String> files,
                           String defaultBranch) {
        StringBuilder text = new StringBuilder(describe(settings));
        text.append("Build command: ").append(build.command()).append('\n');
        text.append("The PR from factory/setup to ").append(defaultBranch).append(" adds these files:\n");
        files.keySet().forEach(path -> text.append("  ").append(path).append('\n'));
        return text.toString();
    }

    /** The summary of worker.yaml only. */
    static String describe(WorkerSettings settings) {
        return "The command writes the file " + settings.configurationFile() + ":\n"
                + "Repository: " + settings.repository().fullName() + '\n'
                + "Work folder: " + settings.workDirectory() + '\n'
                + "Web page: " + describeWebPage(settings.web()) + '\n'
                + "Terminal command: " + settings.terminalCommand().map(command -> String.join(" ", command))
                        .orElse("none") + '\n'
                + "JAVA_HOME: " + settings.javaHome().orElse("not set") + '\n';
    }

    private static String describeWebPage(Optional<WebPage> web) {
        return web.map(page -> page.address() + ":" + page.port() + ", with a password").orElse("none");
    }
}
