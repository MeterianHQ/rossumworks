package com.meterian.factory.setup;

import java.io.Console;
import java.lang.reflect.Method;
import java.util.Optional;

/** The console of the process. It is not interactive when the process has no terminal. */
public final class SystemSetupConsole implements SetupConsole {

    private static final String TERMINAL_CHECK = "isTerminal";

    private final Console console = System.console();

    @Override
    public boolean isInteractive() {
        return isTerminal(console, Console.class);
    }

    /**
     * Tells if the console is a terminal. Java 22 and later give a console also without a terminal, and give the
     * method isTerminal. Java 21 gives a console only for a terminal.
     *
     * @param console     the console, or null when the process has no console
     * @param consoleType the type that can declare the method isTerminal
     */
    static boolean isTerminal(Object console, Class<?> consoleType) {
        if (console == null) {
            return false;
        }
        Optional<Method> terminalCheck = findTerminalCheck(consoleType);
        if (terminalCheck.isEmpty()) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(terminalCheck.get().invoke(console));
        } catch (ReflectiveOperationException | IllegalArgumentException exception) {
            return false;
        }
    }

    private static Optional<Method> findTerminalCheck(Class<?> consoleType) {
        try {
            return Optional.of(consoleType.getMethod(TERMINAL_CHECK));
        } catch (NoSuchMethodException exception) {
            return Optional.empty();
        }
    }

    @Override
    public String ask(String question, String defaultAnswer) throws SetupException {
        String suffix = defaultAnswer.isEmpty() ? "" : " [" + defaultAnswer + "]";
        String answer = console.readLine("%s%s: ", question, suffix);
        if (answer == null) {
            throw new SetupException(INPUT_ENDED);
        }
        return answer.isBlank() ? defaultAnswer : answer.strip();
    }

    @Override
    public Optional<char[]> readPassword(String prompt) {
        return console == null ? Optional.empty() : Optional.ofNullable(console.readPassword("%s", prompt));
    }

    @Override
    public boolean confirm(String question) throws SetupException {
        String answer = console.readLine("%s [y/N]: ", question);
        if (answer == null) {
            throw new SetupException(INPUT_ENDED);
        }
        return answer.strip().equalsIgnoreCase("y") || answer.strip().equalsIgnoreCase("yes");
    }

    @Override
    public void show(String line) {
        System.out.println(line);
    }
}
