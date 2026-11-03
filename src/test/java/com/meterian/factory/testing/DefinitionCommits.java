package com.meterian.factory.testing;

import static com.meterian.factory.testing.TemporaryRepository.run;

import com.meterian.factory.definition.BuildDefinition;
import com.meterian.factory.definition.BuiltInDefinition;
import com.meterian.factory.definition.ClasspathDefinitionFiles;
import com.meterian.factory.definition.DefinitionException;
import com.meterian.factory.definition.DefinitionLoader;
import com.meterian.factory.definition.FactoryDefinition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Writes a folder .factory into a test repository, and makes definitions with the build command of a test. */
public final class DefinitionCommits {

    private static final String FOLDER = ".factory";
    private static final String MAIN_FILE = "factory.yaml";

    private DefinitionCommits() {
    }

    /** The main file of the built-in definition, as the jar ships it. */
    public static String builtInMainFile() {
        try {
            return new ClasspathDefinitionFiles().read(MAIN_FILE).orElseThrow();
        } catch (DefinitionException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** The built-in main file with the build command of a test and no build variables. */
    public static String mainFileFor(String buildCommand) {
        return builtInMainFile()
                .replace("command: ./mvnw verify", "command: " + quoteForYaml(buildCommand))
                .replace("environment: { LANG: C.utf8 }", "environment: {}");
    }

    /** The built-in definition with the build command of a test and no build variables. */
    public static FactoryDefinition builtInWithBuild(String buildCommand) {
        return withBuild(BuiltInDefinition.load(), buildCommand);
    }

    /** The definition with the build command of a test and no build variables. */
    public static FactoryDefinition withBuild(FactoryDefinition definition, String buildCommand) {
        return new FactoryDefinition(definition.workflow(), definition.limits(),
                new BuildDefinition(buildCommand, Map.of()), definition.allowedTools(), definition.mergeTargetBranch(),
                definition.repositories(), definition.prompts());
    }

    /** Loads a definition from the main file and the built-in prompts. */
    public static FactoryDefinition loadMainFile(String mainFile) {
        ClasspathDefinitionFiles builtInFiles = new ClasspathDefinitionFiles();
        try {
            return DefinitionLoader.load(
                    path -> path.equals(MAIN_FILE) ? Optional.of(mainFile) : builtInFiles.read(path));
        } catch (DefinitionException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** The built-in definition with another value of the limit fix-loops. */
    public static FactoryDefinition builtInWithFixLoops(int limit) {
        return loadMainFile(builtInMainFile().replace("fix-loops: 2", "fix-loops: " + limit));
    }

    /**
     * Writes the main file and the built-in prompts into .factory, commits, and pushes to origin main. Without a
     * change, it makes no commit.
     *
     * @return the commit of main after the push
     */
    public static String commitDefinition(Path clone, String mainFile) {
        writeFile(clone.resolve(FOLDER).resolve(MAIN_FILE), mainFile);
        BuiltInDefinition.load().prompts().forEach(
                (relativePath, text) -> writeFile(clone.resolve(FOLDER).resolve(relativePath), text));
        run(clone, "add", FOLDER);
        return commitAndPush(clone, "Change the definition");
    }

    /** Deletes .factory, commits, and pushes to origin main. */
    public static String commitWithoutDefinition(Path clone) {
        run(clone, "rm", "-r", "-q", FOLDER);
        return commitAndPush(clone, "Delete the definition");
    }

    private static String commitAndPush(Path clone, String message) {
        if (!run(clone, "status", "--porcelain").isEmpty()) {
            run(clone, "commit", "-q", "-m", message);
            run(clone, "push", "-q", "origin", "main");
        }
        return run(clone, "rev-parse", "HEAD");
    }

    private static void writeFile(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String quoteForYaml(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
