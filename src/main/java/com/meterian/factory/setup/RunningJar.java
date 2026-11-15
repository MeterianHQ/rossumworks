package com.meterian.factory.setup;

import com.meterian.factory.Main;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Optional;

/** The jar of this process. Outside a jar, for example in a test, there is no jar. */
public final class RunningJar {

    private static final String JAR_SUFFIX = ".jar";

    private RunningJar() {
    }

    public static Optional<Path> find() {
        try {
            CodeSource source = Main.class.getProtectionDomain().getCodeSource();
            if (source == null) {
                return Optional.empty();
            }
            Path location = Path.of(source.getLocation().toURI());
            return location.toString().endsWith(JAR_SUFFIX) ? Optional.of(location) : Optional.empty();
        } catch (URISyntaxException | IllegalArgumentException | SecurityException exception) {
            return Optional.empty();
        }
    }
}
