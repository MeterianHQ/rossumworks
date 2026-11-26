package com.meterian.factory.setup;

import com.meterian.factory.definition.BuiltInDefinition;
import com.meterian.factory.definition.ClasspathDefinitionFiles;
import com.meterian.factory.definition.DefinitionException;
import com.meterian.factory.definition.FactoryDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The default folder .factory: the files of the jar, adapted to the build of the repository, and the run script. */
public final class DefaultFactoryFolder {

    private static final String FOLDER = ".factory/";
    private static final String MAIN_FILE = "factory.yaml";
    private static final String BUILD_COMMAND_LINE = "  command: ./mvnw verify\n";
    private static final String TARGET_BRANCH_LINE = "  target-branch: main\n";
    private static final String RUN_SCRIPT = "run.sh";
    private static final String RUN_SCRIPT_RESOURCE = "/setup/" + RUN_SCRIPT;
    private static final String MAVEN_TOOLS = "\"Bash(./mvnw *)\", \"Bash(mvn *)\"";

    private DefaultFactoryFolder() {
    }

    /** The files by their path relative to the repository, with their text. */
    public static Map<String, String> files(RepositoryBuild build, String targetBranch) {
        Map<String, String> files = new LinkedHashMap<>();
        definitionFiles(build, targetBranch).forEach((path, text) -> files.put(FOLDER + path, text));
        files.put(FOLDER + RUN_SCRIPT, readRunScript());
        return files;
    }

    /** The files of the definition by their path relative to the definition folder. No run script. */
    public static Map<String, String> definitionFiles(RepositoryBuild build, String targetBranch) {
        FactoryDefinition definition = BuiltInDefinition.load();
        Map<String, String> files = new LinkedHashMap<>();
        files.put(MAIN_FILE, adaptMainFile(readResource(MAIN_FILE), build, targetBranch));
        for (String promptPath : definition.prompts().keySet()) {
            files.put(promptPath, readResource(promptPath));
        }
        return files;
    }

    private static String adaptMainFile(String text, RepositoryBuild build, String targetBranch) {
        String withCommand = replaceExactly(text, BUILD_COMMAND_LINE, "  command: " + YamlText.quote(build.command()) + "\n");
        String withBranch = replaceExactly(withCommand, TARGET_BRANCH_LINE, "  target-branch: " + YamlText.quote(targetBranch) + "\n");
        return replaceExactly(withBranch, MAVEN_TOOLS, quoteAll(build.allowedTools()));
    }

    private static String quoteAll(List<String> tools) {
        return tools.stream().map(YamlText::quote).collect(Collectors.joining(", "));
    }

    private static String replaceExactly(String text, String expected, String replacement) {
        if (!text.contains(expected)) {
            throw new IllegalStateException("The built-in " + MAIN_FILE + " has no text " + expected.strip() + ".");
        }
        return text.replace(expected, replacement);
    }

    private static String readResource(String relativePath) {
        try {
            return new ClasspathDefinitionFiles().read(relativePath).orElseThrow(
                    () -> new IllegalStateException("The built-in definition has no file " + relativePath + "."));
        } catch (DefinitionException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    private static String readRunScript() {
        try (InputStream stream = DefaultFactoryFolder.class.getResourceAsStream(RUN_SCRIPT_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("The jar has no file " + RUN_SCRIPT_RESOURCE + ".");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read the file " + RUN_SCRIPT_RESOURCE + ".", exception);
        }
    }
}
