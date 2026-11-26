package com.meterian.factory.setup;

import com.meterian.factory.Main;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Optional;

/**
 * The program of this process: a jar, or a native program. Outside a jar, for example in a test, there is no
 * program.
 *
 * @param path the file of the jar, or the file of the native program
 * @param kind tells how the program starts, and where run.sh keeps its copy
 */
public record RunningProgram(Path path, Kind kind) {

    /** The start command when no program is found: the jar of a release. */
    public static final String DEFAULT_START_COMMAND = "java -jar rossumworks.jar";

    private static final String JAR_SUFFIX = ".jar";
    private static final String NATIVE_IMAGE_PROPERTY = "org.graalvm.nativeimage.imagecode";

    /** The two kinds of program that a release has. */
    public enum Kind {
        JAR("jar", ".rossumworks/jars"),
        NATIVE("native program", ".rossumworks/native");

        private final String description;
        private final String cacheFolder;

        Kind(String description, String cacheFolder) {
            this.description = description;
            this.cacheFolder = cacheFolder;
        }

        public String description() {
            return description;
        }
    }

    public static Optional<RunningProgram> find() {
        if (System.getProperty(NATIVE_IMAGE_PROPERTY) != null) {
            return findNativeProgram();
        }
        return findJar();
    }

    /** The command that starts this program. Without a program, the command of the jar of a release. */
    public static String findStartCommand() {
        return find().map(RunningProgram::startCommand).orElse(DEFAULT_START_COMMAND);
    }

    public String startCommand() {
        return kind == Kind.JAR ? "java -jar " + path : path.toString();
    }

    /** The folder where run.sh looks for a program of this kind before it downloads a release. */
    public Path cacheDirectory(Path home) {
        return home.resolve(kind.cacheFolder);
    }

    private static Optional<RunningProgram> findNativeProgram() {
        return ProcessHandle.current().info().command()
                .map(command -> new RunningProgram(Path.of(command), Kind.NATIVE));
    }

    private static Optional<RunningProgram> findJar() {
        try {
            CodeSource source = Main.class.getProtectionDomain().getCodeSource();
            if (source == null) {
                return Optional.empty();
            }
            Path location = Path.of(source.getLocation().toURI());
            boolean isJar = location.toString().endsWith(JAR_SUFFIX);
            return isJar ? Optional.of(new RunningProgram(location, Kind.JAR)) : Optional.empty();
        } catch (URISyntaxException | IllegalArgumentException | SecurityException exception) {
            return Optional.empty();
        }
    }
}
