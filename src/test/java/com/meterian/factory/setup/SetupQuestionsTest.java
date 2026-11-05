package com.meterian.factory.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.meterian.factory.configuration.RepositoryName;
import com.meterian.factory.web.login.PasswordHash;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SetupQuestionsTest {

    private static final String PASSWORD = "a-long-password-1";
    private static final RepositoryName REPOSITORY = new RepositoryName("acme", "shop");
    private static final Path HOME = Path.of("/home/owner");

    private static CreateOptions options(String... arguments) throws SetupException {
        return CreateOptions.parse(List.of(arguments));
    }

    private static SetupQuestions questions(SetupConsole console, Map<String, String> environment,
                                            boolean hasGnomeTerminal) {
        return new SetupQuestions(console, environment, program -> hasGnomeTerminal, HOME);
    }

    @Test
    void takesTheDefaultsOnEmptyAnswers() throws SetupException {
        FakeSetupConsole console = new FakeSetupConsole(true, List.of(), List.of(PASSWORD, PASSWORD));
        SetupQuestions questions = questions(console, Map.of(), true);
        CreateOptions options = options();

        assertEquals(REPOSITORY, questions.askRepository(options, Optional.of(REPOSITORY)));
        assertEquals(Path.of("/home/owner/.claude-ai-factory/acme-shop"),
                questions.askWorkDirectory(options, REPOSITORY));
        WebPage page = questions.askWeb(options).orElseThrow();
        assertEquals("127.0.0.1", page.address());
        assertEquals(8090, page.port());
        assertEquals(Optional.of(List.of("gnome-terminal", "--")), questions.askTerminalCommand(options));
    }

    @Test
    void aParameterRemovesTheQuestion() throws SetupException {
        FakeSetupConsole console = FakeSetupConsole.answering();
        SetupQuestions questions = questions(console, Map.of(), true);
        CreateOptions options = options("--repository", "acme/shop", "--work-directory", "/w", "--no-web",
                "--terminal-command", "xterm -e", "--build-command", "make");

        questions.askRepository(options, Optional.empty());
        questions.askWorkDirectory(options, REPOSITORY);
        questions.askWeb(options);
        questions.askTerminalCommand(options);
        questions.askBuild(options, Optional.empty(), false);

        assertTrue(console.questions.isEmpty());
    }

    @Test
    void noneGivesNoWebPage() throws SetupException {
        FakeSetupConsole console = FakeSetupConsole.answering("none");

        assertEquals(Optional.empty(), questions(console, Map.of(), false).askWeb(options()));
    }

    @Test
    void refusesAShortPasswordAndAsksAgain() throws SetupException {
        FakeSetupConsole console = FakeSetupConsole.withPasswords("short", "short", PASSWORD, PASSWORD);

        assertTrue(questions(console, Map.of(), false).askWeb(options()).isPresent());
        assertEquals(List.of("The password must have 12 characters or more."), console.lines);
    }

    @Test
    void refusesTwoDifferentPasswordsAndAsksAgain() throws SetupException {
        FakeSetupConsole console = FakeSetupConsole.withPasswords(PASSWORD, PASSWORD + "x", PASSWORD, PASSWORD);

        assertTrue(questions(console, Map.of(), false).askWeb(options()).isPresent());
        assertEquals(List.of("The two passwords are not the same."), console.lines);
    }

    @Test
    void writesOnlyTheHash() throws SetupException {
        FakeSetupConsole console = FakeSetupConsole.withPasswords(PASSWORD, PASSWORD);

        WebPage page = questions(console, Map.of(), false).askWeb(options()).orElseThrow();

        assertTrue(PasswordHash.parse(page.passwordHash()).matches(PASSWORD.toCharArray()));
        assertFalse(page.toString().contains(PASSWORD));
    }

    @Test
    void withoutAConsoleReadsTheVariable() throws SetupException {
        SetupQuestions questions = questions(FakeSetupConsole.withoutTerminal(),
                Map.of("FACTORY_WEB_PASSWORD", PASSWORD), false);

        WebPage page = questions.askWeb(options()).orElseThrow();

        assertTrue(PasswordHash.parse(page.passwordHash()).matches(PASSWORD.toCharArray()));
    }

    @Test
    void withoutAConsoleAChosenAddressNeedsNoQuestion() throws SetupException {
        FakeSetupConsole console = FakeSetupConsole.withoutTerminal();
        SetupQuestions questions = questions(console, Map.of("FACTORY_WEB_PASSWORD", PASSWORD), false);

        assertEquals(9000, questions.askWeb(options("--web", "0.0.0.0:9000")).orElseThrow().port());
        assertTrue(console.questions.isEmpty());
    }

    @Test
    void withoutAConsoleAShortPasswordStops() {
        SetupQuestions questions = questions(FakeSetupConsole.withoutTerminal(),
                Map.of("FACTORY_WEB_PASSWORD", "short"), false);

        SetupException exception = assertThrows(SetupException.class, () -> questions.askWeb(options()));

        assertEquals("The password must have 12 characters or more.", exception.getMessage());
    }

    @Test
    void withoutAConsoleAndWithoutAPasswordStops() {
        SetupQuestions questions = questions(FakeSetupConsole.withoutTerminal(), Map.of(), false);

        SetupException exception = assertThrows(SetupException.class, () -> questions.askWeb(options()));

        assertTrue(exception.getMessage().contains("--no-web"));
        assertTrue(exception.getMessage().contains("FACTORY_WEB_PASSWORD"));
    }

    @Test
    void refusesAPortThatIsNotANumberFrom1To65535() {
        SetupQuestions questions = questions(FakeSetupConsole.withoutTerminal(), Map.of(), false);

        for (String address : List.of("host:abc", "host:0", "host:65536", "host:")) {
            SetupException exception = assertThrows(SetupException.class,
                    () -> questions.askWeb(options("--web", address)));
            assertTrue(exception.getMessage().contains("port"));
        }
    }

    @Test
    void withoutGnomeTerminalAsksNothing() throws SetupException {
        FakeSetupConsole console = FakeSetupConsole.answering();

        assertEquals(Optional.empty(), questions(console, Map.of(), false).askTerminalCommand(options()));
        assertTrue(console.questions.isEmpty());
    }

    @Test
    void findsTheBuildAndAsksWithItsCommand() throws SetupException {
        FakeSetupConsole console = FakeSetupConsole.answering("");

        RepositoryBuild build = questions(console, Map.of(), false)
                .askBuild(options(), Optional.of(RepositoryBuild.MAVEN), true);

        assertEquals(RepositoryBuild.MAVEN, build);
        assertEquals(1, console.questions.size());
    }

    @Test
    void asksAgainWhenNothingIsFoundAndTheAnswerIsEmpty() throws SetupException {
        FakeSetupConsole console = FakeSetupConsole.answering("", "make test");

        RepositoryBuild build = questions(console, Map.of(), false).askBuild(options(), Optional.empty(), false);

        assertEquals("make test", build.command());
        assertEquals(2, console.questions.size());
    }

    @Test
    void theBuildParameterWins() throws SetupException {
        RepositoryBuild build = questions(FakeSetupConsole.answering(), Map.of(), false)
                .askBuild(options("--build-command", "make"), Optional.of(RepositoryBuild.MAVEN), true);

        assertEquals("make", build.command());
        assertTrue(build.isJava());
    }

    @Test
    void withoutAConsoleAndWithoutABuildStops() {
        SetupQuestions questions = questions(FakeSetupConsole.withoutTerminal(), Map.of(), false);

        SetupException exception = assertThrows(SetupException.class,
                () -> questions.askBuild(options(), Optional.empty(), false));

        assertTrue(exception.getMessage().contains("--build-command"));
    }

    @Test
    void withoutAConsoleAndWithoutARepositoryStops() {
        SetupQuestions questions = questions(FakeSetupConsole.withoutTerminal(), Map.of(), false);

        SetupException exception = assertThrows(SetupException.class,
                () -> questions.askRepository(options(), Optional.empty()));

        assertTrue(exception.getMessage().contains("--repository"));
    }

    @Test
    void theConfirmationDefaultIsNo() throws SetupException {
        FakeSetupConsole console = FakeSetupConsole.answering("");

        assertFalse(questions(console, Map.of(), false).confirm(options(), "The summary"));
        assertEquals(List.of("The summary"), console.lines);
    }

    @Test
    void yesGivesTheConfirmation() throws SetupException {
        assertTrue(questions(FakeSetupConsole.withoutTerminal(), Map.of(), false).confirm(options("--yes"), "S"));
    }

    @Test
    void withoutAConsoleTheConfirmationNeedsYes() {
        SetupQuestions questions = questions(FakeSetupConsole.withoutTerminal(), Map.of(), false);

        SetupException exception = assertThrows(SetupException.class, () -> questions.confirm(options(), "S"));

        assertTrue(exception.getMessage().contains("--yes"));
    }
}
