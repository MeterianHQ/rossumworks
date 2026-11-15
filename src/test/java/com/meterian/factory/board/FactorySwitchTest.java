package com.meterian.factory.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FactorySwitchTest {

    @TempDir
    Path temporaryDirectory;

    private Path workDirectory() {
        return temporaryDirectory.resolve("work");
    }

    @Test
    void aNewFactoryRuns() {
        assertThat(new FactorySwitch(workDirectory()).isStopRequested()).isFalse();
    }

    @Test
    void aStopStaysAfterARestart() {
        new FactorySwitch(workDirectory()).requestStop();

        assertThat(new FactorySwitch(workDirectory()).isStopRequested()).isTrue();
    }

    @Test
    void aStartStaysAfterARestart() {
        FactorySwitch factorySwitch = new FactorySwitch(workDirectory());
        factorySwitch.requestStop();

        factorySwitch.start();

        assertThat(factorySwitch.isStopRequested()).isFalse();
        assertThat(Files.exists(workDirectory().resolve(FactorySwitch.MARKER_NAME))).isFalse();
        assertThat(new FactorySwitch(workDirectory()).isStopRequested()).isFalse();
    }

    @Test
    void anEarlierPauseOfThePickUpKeepsTheFactoryStopped() throws IOException {
        Files.createDirectories(workDirectory());
        Files.writeString(workDirectory().resolve(FactorySwitch.EARLIER_MARKER_NAME), "");

        assertThat(new FactorySwitch(workDirectory()).isStopRequested()).isTrue();
    }

    @Test
    void aStartRemovesTheMarkerOfAnEarlierPause() throws IOException {
        Files.createDirectories(workDirectory());
        Files.writeString(workDirectory().resolve(FactorySwitch.EARLIER_MARKER_NAME), "");

        new FactorySwitch(workDirectory()).start();

        assertThat(new FactorySwitch(workDirectory()).isStopRequested()).isFalse();
    }

    @Test
    void theStateFollowsTheStopAndTheIssuesInWork() {
        assertThat(FactoryState.find(false, 2)).isEqualTo(FactoryState.RUNNING);
        assertThat(FactoryState.find(true, 1)).isEqualTo(FactoryState.STOPPING);
        assertThat(FactoryState.find(true, 0)).isEqualTo(FactoryState.STOPPED);
    }
}
