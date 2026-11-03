package com.meterian.factory.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.state.RiskLevel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RiskResultReaderTest {

    @TempDir
    Path worktree;

    @Test
    void aMissingRiskFileIsHighAndNotSmall() {
        RiskResult result = RiskResultReader.read(worktree);

        assertThat(result.risk()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.small()).isFalse();
    }

    @Test
    void readsAValidRiskFile() throws IOException {
        Files.createDirectories(worktree.resolve(".factory-work"));
        Files.writeString(worktree.resolve(".factory-work/risk.json"),
                "{\"risk\": \"low\", \"small\": true, \"reasons\": [\"One line\"]}");

        RiskResult result = RiskResultReader.read(worktree);

        assertThat(result.risk()).isEqualTo(RiskLevel.LOW);
        assertThat(result.small()).isTrue();
        assertThat(result.reasons()).containsExactly("One line");
    }

    @Test
    void aSymbolicLinkIsHighRisk() throws IOException {
        Path real = worktree.resolve("real.json");
        Files.writeString(real, "{\"risk\": \"low\", \"small\": true, \"reasons\": []}");
        Files.createDirectories(worktree.resolve(".factory-work"));
        Files.createSymbolicLink(worktree.resolve(".factory-work/risk.json"), real);

        assertThat(RiskResultReader.read(worktree).risk()).isEqualTo(RiskLevel.HIGH);
    }
}
