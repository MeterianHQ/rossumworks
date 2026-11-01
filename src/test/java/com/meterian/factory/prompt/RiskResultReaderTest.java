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
        Files.createDirectories(worktree.resolve(".factory"));
        Files.writeString(worktree.resolve(".factory/risk.json"),
                "{\"risk\": \"low\", \"small\": true, \"reasons\": [\"One line\"]}");

        RiskResult result = RiskResultReader.read(worktree);

        assertThat(result.risk()).isEqualTo(RiskLevel.LOW);
        assertThat(result.small()).isTrue();
        assertThat(result.reasons()).containsExactly("One line");
    }
}
