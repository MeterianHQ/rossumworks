package com.meterian.factory.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PickUpSwitchTest {

    @TempDir
    Path temporaryDirectory;

    private Path markerFile() {
        return temporaryDirectory.resolve("work").resolve("pick-up-paused");
    }

    @Test
    void aNewSwitchIsNotPaused() {
        assertThat(new PickUpSwitch(markerFile()).isPaused()).isFalse();
    }

    @Test
    void aPauseStaysAfterARestart() {
        new PickUpSwitch(markerFile()).pause();

        assertThat(new PickUpSwitch(markerFile()).isPaused()).isTrue();
    }

    @Test
    void aResumeStaysAfterARestart() {
        PickUpSwitch pickUp = new PickUpSwitch(markerFile());
        pickUp.pause();

        pickUp.resume();

        assertThat(pickUp.isPaused()).isFalse();
        assertThat(Files.exists(markerFile())).isFalse();
        assertThat(new PickUpSwitch(markerFile()).isPaused()).isFalse();
    }
}
