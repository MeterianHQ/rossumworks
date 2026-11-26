package com.meterian.factory;

import com.meterian.factory.Main.StartupException;
import com.meterian.factory.setup.WorkerFileFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** The arguments of the command run: --config <file> and --definition <folder>, in any order. */
public record RunOptions(Optional<Path> configurationFile, Optional<Path> definitionFolder) {

    public static final String USAGE = "Usage: java -jar rossumworks.jar run [--config <file>] "
            + "[--definition <folder>]";
    private static final String COMMAND = "run";
    private static final String CONFIG_OPTION = "--config";
    private static final String DEFINITION_OPTION = "--definition";

    public static RunOptions parse(String[] arguments) throws StartupException {
        if (arguments.length == 0 || !arguments[0].equals(COMMAND)) {
            throw new StartupException(USAGE);
        }
        Optional<Path> configurationFile = Optional.empty();
        Optional<Path> definitionFolder = Optional.empty();
        for (int index = 1; index < arguments.length; index += 2) {
            Path value = Path.of(readValue(arguments, index + 1));
            switch (arguments[index]) {
                case CONFIG_OPTION -> configurationFile = acceptOnce(configurationFile, value);
                case DEFINITION_OPTION -> definitionFolder = acceptOnce(definitionFolder, value);
                default -> throw new StartupException(USAGE);
            }
        }
        return new RunOptions(configurationFile, definitionFolder);
    }

    private static String readValue(String[] arguments, int index) throws StartupException {
        if (index >= arguments.length) {
            throw new StartupException(USAGE);
        }
        return arguments[index];
    }

    private static Optional<Path> acceptOnce(Optional<Path> earlier, Path value) throws StartupException {
        if (earlier.isPresent()) {
            throw new StartupException(USAGE);
        }
        return Optional.of(value);
    }

    /** The file of --config. Without it, the worker.yaml of the clone in the current folder. */
    public Path findConfigurationFile(WorkerFileFinder finder) throws StartupException {
        if (configurationFile.isPresent()) {
            return configurationFile.get();
        }
        return finder.find();
    }

    /** The absolute path of the folder of --definition, after a check that the folder exists. */
    public Optional<Path> verifyDefinitionFolder() throws StartupException {
        if (definitionFolder.isEmpty()) {
            return Optional.empty();
        }
        Path folder = definitionFolder.get().toAbsolutePath();
        if (!Files.exists(folder)) {
            throw new StartupException("The definition folder " + folder + " does not exist.");
        }
        if (!Files.isDirectory(folder)) {
            throw new StartupException("The definition folder " + folder + " is not a folder.");
        }
        return Optional.of(folder);
    }
}
