package com.meterian.factory.setup;

import com.meterian.factory.configuration.ConfigurationException;
import com.meterian.factory.configuration.RepositoryName;
import com.meterian.factory.web.login.PasswordHash;
import com.meterian.factory.web.login.PasswordRules;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/** The questions of the create command, with their defaults. Without a console, a default or a parameter answers. */
public final class SetupQuestions {

    static final String DEFAULT_WEB = "127.0.0.1:8090";
    static final String NO_WEB_ANSWER = "none";
    static final String PASSWORD_VARIABLE = "FACTORY_WEB_PASSWORD";
    static final String TERMINAL_PROGRAM = "gnome-terminal";
    static final String DEFAULT_TERMINAL_COMMAND = "gnome-terminal --";
    private static final int MAXIMUM_PORT = 65535;

    private final SetupConsole console;
    private final Map<String, String> environment;
    private final Predicate<String> isOnPath;
    private final Path home;

    public SetupQuestions(SetupConsole console, Map<String, String> environment, Predicate<String> isOnPath,
                          Path home) {
        this.console = console;
        this.environment = environment;
        this.isOnPath = isOnPath;
        this.home = home;
    }

    public RepositoryName askRepository(CreateOptions options, Optional<RepositoryName> fromRemote)
            throws SetupException {
        if (options.repository().isPresent()) {
            return options.repository().get();
        }
        if (!console.isInteractive()) {
            return fromRemote.orElseThrow(() -> new SetupException(
                    "The command cannot find the GitHub repository. Give --repository owner/name."));
        }
        String defaultAnswer = fromRemote.map(RepositoryName::fullName).orElse("");
        while (true) {
            String answer = console.ask("The starting repository (owner/name)", defaultAnswer);
            try {
                return RepositoryName.parse(answer);
            } catch (ConfigurationException exception) {
                console.show("The repository must have the form owner/name, for example MeterianHQ/heidi-fix.");
            }
        }
    }

    public Path askWorkDirectory(CreateOptions options, RepositoryName repository) throws SetupException {
        if (options.workDirectory().isPresent()) {
            return options.workDirectory().get();
        }
        String defaultAnswer = home.resolve(".claude-ai-factory")
                .resolve(repository.owner() + "-" + repository.name()).toString();
        if (!console.isInteractive()) {
            return Path.of(defaultAnswer);
        }
        return Path.of(console.ask("The work folder", defaultAnswer));
    }

    public Optional<WebPage> askWeb(CreateOptions options) throws SetupException {
        if (options.noWeb()) {
            return Optional.empty();
        }
        String address = chooseWebAddress(options);
        if (address.equalsIgnoreCase(NO_WEB_ANSWER)) {
            return Optional.empty();
        }
        int separator = address.lastIndexOf(':');
        if (separator < 1) {
            throw new SetupException("The web address must have the form address:port, for example " + DEFAULT_WEB + ".");
        }
        int port = parsePort(address.substring(separator + 1));
        return Optional.of(new WebPage(address.substring(0, separator), port, readPasswordHash()));
    }

    private String chooseWebAddress(CreateOptions options) throws SetupException {
        if (options.web().isPresent()) {
            return options.web().get();
        }
        if (!console.isInteractive()) {
            return DEFAULT_WEB;
        }
        return console.ask("The web page address (address:port, or " + NO_WEB_ANSWER + " for no page)", DEFAULT_WEB);
    }

    private static int parsePort(String text) throws SetupException {
        try {
            int port = Integer.parseInt(text);
            if (port >= 1 && port <= MAXIMUM_PORT) {
                return port;
            }
        } catch (NumberFormatException exception) {
            // The message below tells the owner.
        }
        throw new SetupException("The port of the web page must be a number from 1 to " + MAXIMUM_PORT + ".");
    }

    private String readPasswordHash() throws SetupException {
        return console.isInteractive() ? askPasswordHash() : readPasswordHashFromEnvironment();
    }

    private String readPasswordHashFromEnvironment() throws SetupException {
        String password = environment.get(PASSWORD_VARIABLE);
        if (password == null || password.isEmpty()) {
            throw new SetupException("Give --no-web, or set " + PASSWORD_VARIABLE + ".");
        }
        char[] typed = password.toCharArray();
        char[] repeated = password.toCharArray();
        try {
            Optional<String> problem = PasswordRules.verify(typed, repeated);
            if (problem.isPresent()) {
                throw new SetupException(problem.get());
            }
            return PasswordHash.create(typed).format();
        } finally {
            Arrays.fill(typed, '\0');
            Arrays.fill(repeated, '\0');
        }
    }

    private String askPasswordHash() throws SetupException {
        while (true) {
            char[] typed = console.readPassword("Password for the web page: ")
                    .orElseThrow(() -> new SetupException(SetupConsole.INPUT_ENDED));
            char[] repeated = console.readPassword("Repeat the password: ")
                    .orElseThrow(() -> new SetupException(SetupConsole.INPUT_ENDED));
            try {
                Optional<String> problem = PasswordRules.verify(typed, repeated);
                if (problem.isEmpty()) {
                    return PasswordHash.create(typed).format();
                }
                console.show(problem.get());
            } finally {
                Arrays.fill(typed, '\0');
                Arrays.fill(repeated, '\0');
            }
        }
    }

    public Optional<List<String>> askTerminalCommand(CreateOptions options) throws SetupException {
        if (options.terminalCommand().isPresent()) {
            return options.terminalCommand();
        }
        if (!isOnPath.test(TERMINAL_PROGRAM)) {
            return Optional.empty();
        }
        String answer = console.isInteractive()
                ? console.ask("The terminal command", DEFAULT_TERMINAL_COMMAND)
                : DEFAULT_TERMINAL_COMMAND;
        return Optional.of(Arrays.stream(answer.strip().split("\\s+")).toList());
    }

    public RepositoryBuild askBuild(CreateOptions options, Optional<RepositoryBuild> found, boolean isJava)
            throws SetupException {
        if (options.buildCommand().isPresent()) {
            return RepositoryBuild.custom(options.buildCommand().get(), isJava);
        }
        if (!console.isInteractive()) {
            return found.orElseThrow(() -> new SetupException(
                    "The command cannot find the build of the repository. Give --build-command \"<command>\"."));
        }
        if (found.isPresent()) {
            String answer = console.ask("The build command", found.get().command());
            return answer.equals(found.get().command()) ? found.get() : RepositoryBuild.custom(answer, isJava);
        }
        return RepositoryBuild.custom(askBuildCommandWithoutDefault(), isJava);
    }

    private String askBuildCommandWithoutDefault() throws SetupException {
        while (true) {
            String answer = console.ask("The build command of the repository", "");
            if (!answer.isBlank()) {
                return answer;
            }
            console.show("The build command is necessary.");
        }
    }

    public boolean confirm(CreateOptions options, String summary) throws SetupException {
        if (options.yes()) {
            return true;
        }
        if (!console.isInteractive()) {
            throw new SetupException("The command needs a confirmation. Give --yes.");
        }
        console.show(summary);
        return console.confirm("Write the files and open the pull request?");
    }
}
