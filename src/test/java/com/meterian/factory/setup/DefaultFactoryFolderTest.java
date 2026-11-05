package com.meterian.factory.setup;

import com.meterian.factory.definition.BuiltInDefinition;
import com.meterian.factory.definition.DefinitionFiles;
import com.meterian.factory.definition.DefinitionLoader;
import com.meterian.factory.definition.FactoryDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultFactoryFolderTest {

    @TempDir
    Path repositoryFolder;

    static Stream<RepositoryBuild> builds() {
        return Stream.of(RepositoryBuild.MAVEN, RepositoryBuild.GRADLE, RepositoryBuild.NPM,
                RepositoryBuild.custom("make test", false));
    }

    @Test
    void copiesEveryFileOfTheBuiltInDefinition() {
        Set<String> expected = new HashSet<>();
        expected.add(".factory/factory.yaml");
        BuiltInDefinition.load().prompts().keySet().forEach(path -> expected.add(".factory/" + path));

        Map<String, String> files = DefaultFactoryFolder.files(RepositoryBuild.MAVEN, "main");

        assertThat(files.keySet()).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("builds")
    void loadsForEachBuild(RepositoryBuild build) throws Exception {
        writeFiles(DefaultFactoryFolder.files(build, "develop"));

        FactoryDefinition definition = DefinitionLoader.load(filesOfFolder(repositoryFolder.resolve(".factory")));

        assertThat(definition.build().command()).isEqualTo(build.command());
        assertThat(definition.allowedTools()).contains("Read").containsAll(build.allowedTools());
        if (build != RepositoryBuild.MAVEN) {
            assertThat(definition.allowedTools()).doesNotContain("Bash(./mvnw *)", "Bash(mvn *)");
        }
        assertThat(definition.mergeTargetBranch()).isEqualTo("develop");
    }

    private void writeFiles(Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = repositoryFolder.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
    }

    private static DefinitionFiles filesOfFolder(Path folder) {
        return relativePath -> {
            Path path = folder.resolve(relativePath);
            try {
                return Files.exists(path) ? Optional.of(Files.readString(path)) : Optional.empty();
            } catch (IOException exception) {
                throw new com.meterian.factory.definition.DefinitionException(exception.getMessage(), exception);
            }
        };
    }
}
