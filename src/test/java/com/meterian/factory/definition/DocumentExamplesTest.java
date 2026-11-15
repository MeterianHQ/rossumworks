package com.meterian.factory.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Loads each full example of the reference docs/factory-definition.md. A full example is a yaml block that starts
 * with "version: 1". A block that starts with another key is a fragment, and the test does not load it.
 */
class DocumentExamplesTest {

    private static final Path REFERENCE = Path.of("docs", "factory-definition.md");
    private static final Pattern YAML_BLOCK = Pattern.compile("```yaml\\n(.*?)```", Pattern.DOTALL);
    private static final String FULL_EXAMPLE_START = "version: 1\n";

    @TestFactory
    Stream<DynamicTest> loadsEachFullExample() throws IOException {
        List<String> examples = findFullExamples();
        assertThat(examples).isNotEmpty();
        return IntStream.range(0, examples.size()).mapToObj(index -> DynamicTest.dynamicTest(
                "the full example number " + (index + 1),
                () -> assertThatCode(() -> DefinitionLoader.load(createFiles(examples.get(index))))
                        .doesNotThrowAnyException()));
    }

    @Test
    void showsTheDefaultWorkflowOfTheJar() throws Exception {
        String defaultWorkflow = new ClasspathDefinitionFiles().read(DefinitionNode.MAIN_FILE).orElseThrow();

        assertThat(findFullExamples()).contains(defaultWorkflow);
    }

    private static List<String> findFullExamples() throws IOException {
        Matcher matcher = YAML_BLOCK.matcher(Files.readString(REFERENCE));
        return matcher.results()
                .map(result -> result.group(1))
                .filter(block -> block.startsWith(FULL_EXAMPLE_START))
                .toList();
    }

    /** The example is the main file. The prompt files come from the jar. */
    private static DefinitionFiles createFiles(String example) {
        ClasspathDefinitionFiles jarFiles = new ClasspathDefinitionFiles();
        return relativePath -> relativePath.equals(DefinitionNode.MAIN_FILE)
                ? Optional.of(example)
                : jarFiles.read(relativePath);
    }
}
