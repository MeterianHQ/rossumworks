package com.meterian.factory.setup;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeneratedFactoryFolderTest {

    @Test
    void acceptsTheDefaultFolder() {
        Map<String, String> files = DefaultFactoryFolder.files(RepositoryBuild.MAVEN, "main");

        assertThatCode(() -> GeneratedFactoryFolder.verifyLoads(files)).doesNotThrowAnyException();
    }

    @Test
    void refusesAFolderThatDoesNotLoad() {
        Map<String, String> files = new LinkedHashMap<>(DefaultFactoryFolder.files(RepositoryBuild.MAVEN, "main"));
        files.put(".factory/factory.yaml", "version: 1\n");

        assertThatThrownBy(() -> GeneratedFactoryFolder.verifyLoads(files))
                .isInstanceOf(SetupException.class)
                .hasMessageStartingWith("The generated .factory does not load: ");
    }

    @Test
    void refusesAFolderWithoutTheMainFile() {
        assertThatThrownBy(() -> GeneratedFactoryFolder.verifyLoads(Map.of()))
                .isInstanceOf(SetupException.class)
                .hasMessageStartingWith("The generated .factory does not load: ");
    }
}
