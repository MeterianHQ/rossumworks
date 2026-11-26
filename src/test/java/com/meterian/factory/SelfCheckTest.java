package com.meterian.factory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SelfCheckTest {

    private final ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
    private final PrintStream output = new PrintStream(outputBytes, true, StandardCharsets.UTF_8);

    private String outputText() {
        return outputBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void allTheStepsOfTheFactoryPass() {
        int exitCode = SelfCheck.withAllSteps(output).run();

        assertThat(outputText()).isEqualTo(String.join(System.lineSeparator(),
                "ok: version", "ok: definition", "ok: status comment", "ok: events", "ok: live files",
                "ok: gatekeeper", "ok: board", "The self-check passed.") + System.lineSeparator());
        assertThat(exitCode).isEqualTo(0);
    }

    @Test
    void aFailedStepGivesItsProblemAndTheNextStepsStillRun() {
        SelfCheckStep broken = new SelfCheckStep("broken", folder -> {
            throw new IllegalStateException("The resource is missing.");
        });
        SelfCheckStep good = new SelfCheckStep("good", folder -> { });

        int exitCode = new SelfCheck(List.of(broken, good), output).run();

        assertThat(outputText()).isEqualTo(String.join(System.lineSeparator(),
                "failed: broken: The resource is missing.", "ok: good",
                "The self-check failed. The program is not complete.") + System.lineSeparator());
        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    void eachStepGetsAnEmptyFolderThatIsDeletedAfterTheStep() {
        List<Path> folders = new ArrayList<>();
        SelfCheckStep writer = new SelfCheckStep("writer", folder -> {
            assertThat(folder).isEmptyDirectory();
            folders.add(folder);
            Files.writeString(Files.createDirectories(folder.resolve("sub")).resolve("file"), "text");
        });

        new SelfCheck(List.of(writer, writer), output).run();

        assertThat(folders).hasSize(2).doesNotHaveDuplicates().allSatisfy(folder -> assertThat(folder).doesNotExist());
    }

    @Test
    void theSelfCheckCommandIsSelectedByItsName() {
        assertThat(Main.isSelfCheckCommand(new String[] {"self-check"})).isTrue();
        assertThat(Main.isSelfCheckCommand(new String[] {"self-check", "x"})).isFalse();
        assertThat(Main.isSelfCheckCommand(new String[] {"run"})).isFalse();
    }
}
