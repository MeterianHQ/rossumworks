package com.meterian.factory.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CloneLockTest {

    @Test
    void givesOneLockForEachClone() {
        Path clone = Path.of("clone").toAbsolutePath();

        assertThat(CloneLock.of(Path.of("clone"))).isSameAs(CloneLock.of(clone));
        assertThat(CloneLock.of(clone.resolve("..").resolve("clone"))).isSameAs(CloneLock.of(clone));
        assertThat(CloneLock.of(Path.of("other-clone"))).isNotSameAs(CloneLock.of(clone));
    }

    @Test
    void runsTheActionAndReturnsItsResult() {
        assertThat(CloneLock.run(Path.of("clone"), () -> "result")).isEqualTo("result");
    }
}
