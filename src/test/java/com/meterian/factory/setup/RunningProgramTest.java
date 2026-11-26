package com.meterian.factory.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RunningProgramTest {

    private static final Path HOME = Path.of("/home/user");

    @Test
    void aJarStartsWithJava() {
        RunningProgram jar = new RunningProgram(Path.of("/opt/rossumworks-0.6.0.jar"), RunningProgram.Kind.JAR);

        assertThat(jar.startCommand()).isEqualTo("java -jar /opt/rossumworks-0.6.0.jar");
        assertThat(jar.cacheDirectory(HOME)).isEqualTo(Path.of("/home/user/.rossumworks/jars"));
        assertThat(jar.kind().description()).isEqualTo("jar");
    }

    @Test
    void aNativeProgramStartsWithItsPath() {
        RunningProgram program = new RunningProgram(Path.of("/opt/rossumworks-0.6.0-linux-x64"),
                RunningProgram.Kind.NATIVE);

        assertThat(program.startCommand()).isEqualTo("/opt/rossumworks-0.6.0-linux-x64");
        assertThat(program.cacheDirectory(HOME)).isEqualTo(Path.of("/home/user/.rossumworks/native"));
        assertThat(program.kind().description()).isEqualTo("native program");
    }

    @Test
    void theClassesOfATestAreNoProgram() {
        assertThat(RunningProgram.find()).isEmpty();
    }

    @Test
    void withoutAProgramTheStartCommandNamesTheJar() {
        assertThat(RunningProgram.findStartCommand()).isEqualTo("java -jar rossumworks.jar");
    }
}
