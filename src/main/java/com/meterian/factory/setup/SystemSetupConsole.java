package com.meterian.factory.setup;

import java.io.Console;
import java.util.Optional;

/** The console of the process. It is not interactive when the process has no terminal. */
public final class SystemSetupConsole implements SetupConsole {

    private final Console console = System.console();

    @Override
    public boolean isInteractive() {
        return console != null;
    }

    @Override
    public String ask(String question, String defaultAnswer) {
        String suffix = defaultAnswer.isEmpty() ? "" : " [" + defaultAnswer + "]";
        String answer = console.readLine("%s%s: ", question, suffix);
        return answer == null || answer.isBlank() ? defaultAnswer : answer.strip();
    }

    @Override
    public Optional<char[]> readPassword(String prompt) {
        return console == null ? Optional.empty() : Optional.ofNullable(console.readPassword("%s", prompt));
    }

    @Override
    public boolean confirm(String question) {
        String answer = console.readLine("%s [y/N]: ", question);
        return answer != null && (answer.strip().equalsIgnoreCase("y") || answer.strip().equalsIgnoreCase("yes"));
    }

    @Override
    public void show(String line) {
        System.out.println(line);
    }
}
