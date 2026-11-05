package com.meterian.factory.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A console for the tests. It gives the answers in order and records the questions. */
final class FakeSetupConsole implements SetupConsole {

    final List<String> questions = new ArrayList<>();
    final List<String> lines = new ArrayList<>();
    private final boolean interactive;
    private final List<String> answers;
    private final List<String> passwords;

    FakeSetupConsole(boolean interactive, List<String> answers, List<String> passwords) {
        this.interactive = interactive;
        this.answers = new ArrayList<>(answers);
        this.passwords = new ArrayList<>(passwords);
    }

    static FakeSetupConsole answering(String... answers) {
        return new FakeSetupConsole(true, List.of(answers), List.of());
    }

    static FakeSetupConsole withPasswords(String... passwords) {
        return new FakeSetupConsole(true, List.of(), List.of(passwords));
    }

    static FakeSetupConsole withoutTerminal() {
        return new FakeSetupConsole(false, List.of(), List.of());
    }

    @Override
    public boolean isInteractive() {
        return interactive;
    }

    @Override
    public String ask(String question, String defaultAnswer) {
        questions.add(question);
        String answer = answers.isEmpty() ? "" : answers.remove(0);
        return answer.isEmpty() ? defaultAnswer : answer;
    }

    @Override
    public Optional<char[]> readPassword(String prompt) {
        questions.add(prompt);
        return passwords.isEmpty() ? Optional.empty() : Optional.of(passwords.remove(0).toCharArray());
    }

    @Override
    public boolean confirm(String question) {
        questions.add(question);
        String answer = answers.isEmpty() ? "" : answers.remove(0);
        return answer.equalsIgnoreCase("y");
    }

    @Override
    public void show(String line) {
        lines.add(line);
    }
}
