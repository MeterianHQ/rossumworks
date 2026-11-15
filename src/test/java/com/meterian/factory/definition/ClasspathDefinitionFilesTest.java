package com.meterian.factory.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ClasspathDefinitionFilesTest {

    private final ClasspathDefinitionFiles files = new ClasspathDefinitionFiles();

    @Test
    void readsAFileOfTheJar() throws DefinitionException {
        assertThat(files.read("factory.yaml")).isPresent();
    }

    @Test
    void answersEmptyForAMissingFile() throws DefinitionException {
        assertThat(files.read("prompts/missing.md")).isEmpty();
    }

    @Test
    void refusesAPathThatLeavesTheFolder() {
        assertThatThrownBy(() -> files.read("../prompts/fix.md")).isInstanceOf(DefinitionException.class)
                .hasMessage("The prompt path ../prompts/fix.md leaves the folder .factory.");
        assertThatThrownBy(() -> files.read("prompts/../../x")).isInstanceOf(DefinitionException.class);
        assertThatThrownBy(() -> files.read("/etc/passwd")).isInstanceOf(DefinitionException.class);
    }
}
