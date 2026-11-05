package com.meterian.factory.setup;

import com.meterian.factory.configuration.ConfigurationException;
import com.meterian.factory.configuration.RepositoryName;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** The parameters of the create command. A parameter gives the answer and removes the question. */
public record CreateOptions(
        Optional<RepositoryName> repository,
        Optional<Path> workDirectory,
        Optional<String> web,
        boolean noWeb,
        Optional<List<String>> terminalCommand,
        Optional<String> buildCommand,
        boolean yes) {

    public static final String USAGE = "Usage: java -jar claude-ai-factory.jar create [--repository owner/name]\n"
            + "       [--work-directory <path>] [--web <address:port> | --no-web]\n"
            + "       [--terminal-command \"<command>\"] [--build-command \"<command>\"] [--yes]";

    public static CreateOptions parse(List<String> arguments) throws SetupException {
        Optional<RepositoryName> repository = Optional.empty();
        Optional<Path> workDirectory = Optional.empty();
        Optional<String> web = Optional.empty();
        boolean noWeb = false;
        Optional<List<String>> terminalCommand = Optional.empty();
        Optional<String> buildCommand = Optional.empty();
        boolean yes = false;
        for (int index = 0; index < arguments.size(); index++) {
            String parameter = arguments.get(index);
            switch (parameter) {
                case "--no-web" -> noWeb = true;
                case "--yes" -> yes = true;
                case "--repository" -> repository = Optional.of(parseRepository(readValue(arguments, ++index, parameter)));
                case "--work-directory" -> workDirectory = Optional.of(Path.of(readValue(arguments, ++index, parameter)));
                case "--web" -> web = Optional.of(readValue(arguments, ++index, parameter));
                case "--terminal-command" -> terminalCommand = Optional.of(splitCommand(readValue(arguments, ++index, parameter)));
                case "--build-command" -> buildCommand = Optional.of(readValue(arguments, ++index, parameter));
                default -> throw new SetupException("Unknown parameter " + parameter + ".\n" + USAGE);
            }
        }
        if (web.isPresent() && noWeb) {
            throw new SetupException("Give --web or --no-web, not both.\n" + USAGE);
        }
        return new CreateOptions(repository, workDirectory, web, noWeb, terminalCommand, buildCommand, yes);
    }

    private static String readValue(List<String> arguments, int index, String parameter) throws SetupException {
        if (index >= arguments.size()) {
            throw new SetupException("The parameter " + parameter + " needs a value.\n" + USAGE);
        }
        return arguments.get(index);
    }

    private static RepositoryName parseRepository(String value) throws SetupException {
        try {
            return RepositoryName.parse(value);
        } catch (ConfigurationException exception) {
            throw new SetupException("The repository must have the form owner/name, for example MeterianHQ/heidi-fix.");
        }
    }

    private static List<String> splitCommand(String command) {
        return Arrays.stream(command.strip().split("\\s+")).toList();
    }
}
