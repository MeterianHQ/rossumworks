package com.meterian.factory.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.meterian.factory.configuration.RepositoryName;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CreateOptionsTest {

    @Test
    void givesEmptyOptionsWithoutParameters() throws SetupException {
        CreateOptions options = CreateOptions.parse(List.of());

        assertEquals(Optional.empty(), options.repository());
        assertEquals(Optional.empty(), options.web());
        assertFalse(options.noWeb());
        assertFalse(options.yes());
    }

    @Test
    void readsEachParameter() throws SetupException {
        CreateOptions options = CreateOptions.parse(List.of("--repository", "acme/shop", "--work-directory", "/tmp/w",
                "--web", "0.0.0.0:9000", "--terminal-command", "xterm -e", "--build-command", "make test", "--yes"));

        assertEquals(Optional.of(new RepositoryName("acme", "shop")), options.repository());
        assertEquals(Optional.of(Path.of("/tmp/w")), options.workDirectory());
        assertEquals(Optional.of("0.0.0.0:9000"), options.web());
        assertEquals(Optional.of(List.of("xterm", "-e")), options.terminalCommand());
        assertEquals(Optional.of("make test"), options.buildCommand());
        assertTrue(options.yes());
    }

    @Test
    void readsNoWeb() throws SetupException {
        assertTrue(CreateOptions.parse(List.of("--no-web")).noWeb());
    }

    @Test
    void refusesAnUnknownParameter() {
        SetupException exception = assertThrows(SetupException.class, () -> CreateOptions.parse(List.of("--bad")));

        assertTrue(exception.getMessage().contains("--bad"));
        assertTrue(exception.getMessage().contains("Usage"));
    }

    @Test
    void refusesWebWithNoWeb() {
        assertThrows(SetupException.class, () -> CreateOptions.parse(List.of("--web", "a:1", "--no-web")));
    }

    @Test
    void refusesAMissingValue() {
        SetupException exception = assertThrows(SetupException.class,
                () -> CreateOptions.parse(List.of("--repository")));

        assertTrue(exception.getMessage().contains("--repository"));
    }

    @Test
    void refusesABlankBuildCommand() {
        for (String blank : List.of("", "  ")) {
            SetupException exception = assertThrows(SetupException.class,
                    () -> CreateOptions.parse(List.of("--build-command", blank)));

            assertTrue(exception.getMessage().contains("--build-command"));
        }
    }
}
