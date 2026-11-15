package com.meterian.factory.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A console for the tests. It gives the answers in order and records the questions. */
final class FakeSetupConsole implements SetupConsole {

    final List<String> questions = new ArrayList<>();
    final List<String> lines = new ArrayList<>();
    private final boolean interactive;
    private final boolean endsWhenAnswersRunOut;
    private final List<String> answers;
    private final List<String> passwords;

    FakeSetupConsole(boolean interactive, List<String> answers, List<String> passwords) {
        this(interactive, answers, passwords, false);
    }

    private FakeSetupConsole(boolean interactive, List<String> answers, List<String> passwords,
                             boolean endsWhenAnswersRunOut) {
        this.interactive = interactive;
        this.endsWhenAnswersRunOut = endsWhenAnswersRunOut;
        this.answers = new ArrayList<>(answers);
        this.passwords = new ArrayList<>(passwords);
    }

    static FakeSetupConsole answering(String... answers) {
        return new FakeSetupConsole(true, List.of(answers), List.of());
    }

    static FakeSetupConsole withPasswords(String... passwords) {
        return new FakeSetupConsole(true, List.of(), List.of(passwords));
    }

    /** The input ends when the answers and the passwords run out, as after Ctrl-D. */
    static FakeSetupConsole endingInput(List<String> answers, List<String> passwords) {
        return new FakeSetupConsole(true, answers, passwords, true);
    }

    static FakeSetupConsole withoutTerminal() {
        return new FakeSetupConsole(false, List.of(), List.of());
    }

    @Override
    public boolean isInteractive() {
        return interactive;
    }

    @Override
    public String ask(String question, String defaultAnswer) throws SetupException {
        questions.add(question);
        String answer = nextAnswer();
        return answer.isEmpty() ? defaultAnswer : answer;
    }

    @Override
    public Optional<char[]> readPassword(String prompt) {
        questions.add(prompt);
        return passwords.isEmpty() ? Optional.empty() : Optional.of(passwords.remove(0).toCharArray());
    }

    @Override
    public boolean confirm(String question) throws SetupException {
        questions.add(question);
        String answer = nextAnswer();
        return answer.equalsIgnoreCase("y");
    }

    private String nextAnswer() throws SetupException {
        if (answers.isEmpty() && endsWhenAnswersRunOut) {
            throw new SetupException(SetupConsole.INPUT_ENDED);
        }
        return answers.isEmpty() ? "" : answers.remove(0);
    }

    @Override
    public void show(String line) {
        lines.add(line);
    }
}
