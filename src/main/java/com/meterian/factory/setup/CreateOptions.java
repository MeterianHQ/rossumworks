package com.meterian.factory.setup;

import com.meterian.factory.configuration.ConfigurationException;
import com.meterian.factory.configuration.ProviderName;
import com.meterian.factory.configuration.RepositoryName;
import java.math.BigDecimal;
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
        boolean yes,
        Optional<ProviderName> provider,
        Optional<BigDecimal> dailyBudgetUsd,
        Optional<Path> definitionFolder) {

    public static final String USAGE = "Usage: java -jar rossumworks.jar create [--repository owner/name]\n"
            + "       [--work-directory <path>] [--web <address:port> | --no-web]\n"
            + "       [--terminal-command \"<command>\"] [--build-command \"<command>\"]\n"
            + "       [--provider subscription|anthropic-api-key] [--daily-budget-usd <amount>]\n"
            + "       [--definition <folder>] [--yes]";

    public static CreateOptions parse(List<String> arguments) throws SetupException {
        Optional<RepositoryName> repository = Optional.empty();
        Optional<Path> workDirectory = Optional.empty();
        Optional<String> web = Optional.empty();
        boolean noWeb = false;
        Optional<List<String>> terminalCommand = Optional.empty();
        Optional<String> buildCommand = Optional.empty();
        boolean yes = false;
        Optional<ProviderName> provider = Optional.empty();
        Optional<BigDecimal> dailyBudgetUsd = Optional.empty();
        Optional<Path> definitionFolder = Optional.empty();
        for (int index = 0; index < arguments.size(); index++) {
            String parameter = arguments.get(index);
            switch (parameter) {
                case "--no-web" -> noWeb = true;
                case "--yes" -> yes = true;
                case "--repository" -> repository = Optional.of(parseRepository(readValue(arguments, ++index, parameter)));
                case "--work-directory" -> workDirectory = Optional.of(Path.of(readValue(arguments, ++index, parameter)));
                case "--web" -> web = Optional.of(readValue(arguments, ++index, parameter));
                case "--terminal-command" -> terminalCommand = Optional.of(splitCommand(readValue(arguments, ++index, parameter)));
                case "--build-command" -> buildCommand = Optional.of(readBuildCommand(arguments, ++index, parameter));
                case "--provider" -> provider = Optional.of(parseProvider(readValue(arguments, ++index, parameter)));
                case "--daily-budget-usd" ->
                        dailyBudgetUsd = Optional.of(parseDailyBudget(readValue(arguments, ++index, parameter)));
                case "--definition" -> definitionFolder = Optional.of(Path.of(readValue(arguments, ++index, parameter)));
                default -> throw new SetupException("Unknown parameter " + parameter + ".\n" + USAGE);
            }
        }
        if (web.isPresent() && noWeb) {
            throw new SetupException("Give --web or --no-web, not both.\n" + USAGE);
        }
        return new CreateOptions(repository, workDirectory, web, noWeb, terminalCommand, buildCommand, yes,
                provider, dailyBudgetUsd, definitionFolder);
    }

    private static String readValue(List<String> arguments, int index, String parameter) throws SetupException {
        if (index >= arguments.size()) {
            throw new SetupException("The parameter " + parameter + " needs a value.\n" + USAGE);
        }
        return arguments.get(index);
    }

    private static String readBuildCommand(List<String> arguments, int index, String parameter)
            throws SetupException {
        String command = readValue(arguments, index, parameter);
        if (command.isBlank()) {
            throw new SetupException("The parameter " + parameter + " needs a command that is not blank.\n" + USAGE);
        }
        return command;
    }

    private static RepositoryName parseRepository(String value) throws SetupException {
        try {
            return RepositoryName.parse(value);
        } catch (ConfigurationException exception) {
            throw new SetupException("The repository must have the form owner/name, for example octocat/hello-world.");
        }
    }

    private static ProviderName parseProvider(String value) throws SetupException {
        return ProviderName.findByConfigurationValue(value).orElseThrow(() -> new SetupException(
                "The provider must be subscription or anthropic-api-key.\n" + USAGE));
    }

    private static BigDecimal parseDailyBudget(String value) throws SetupException {
        return ProviderChoice.parseDailyBudget(value).orElseThrow(() -> new SetupException(
                "The daily budget must be a number from 0.01 to 1000000.\n" + USAGE));
    }

    private static List<String> splitCommand(String command) {
        return Arrays.stream(command.strip().split("\\s+")).toList();
    }
}
