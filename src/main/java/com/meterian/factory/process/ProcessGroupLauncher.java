package com.meterian.factory.process;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Prepares a command that leads a new process group, so that ProcessGroup can end the command with its
 * descendants. Linux has the command setsid. macOS has no setsid command, so perl makes the system call there.
 */
public final class ProcessGroupLauncher {

    private static final String SETSID_PROGRAM = "setsid";
    private static final String PERL_PROGRAM = "perl";
    private static final String LOCALE_VARIABLE = "LC_ALL";
    private static final String SAFE_LOCALE = "C";
    private static final String SAVED_LOCALE_VARIABLE = "FACTORY_SAVED_LC_ALL";
    private static final String PERL_SCRIPT = """
            use POSIX qw(setsid);
            if (exists $ENV{FACTORY_SAVED_LC_ALL}) {
                $ENV{LC_ALL} = delete $ENV{FACTORY_SAVED_LC_ALL};
            } else {
                delete $ENV{LC_ALL};
            }
            defined(setsid()) or die "The system call setsid failed: $!\\n";
            exec { $ARGV[0] } @ARGV;
            print STDERR "Cannot run $ARGV[0]: $!\\n";
            exit 127;
            """;

    private final OperatingSystem operatingSystem;

    public ProcessGroupLauncher(OperatingSystem operatingSystem) {
        this.operatingSystem = operatingSystem;
    }

    public static ProcessGroupLauncher createForThisSystem() {
        return new ProcessGroupLauncher(OperatingSystem.detect());
    }

    /** The program that starts the process group. It must be on the PATH. */
    public String program() {
        return switch (operatingSystem) {
            case LINUX -> SETSID_PROGRAM;
            case MACOS -> PERL_PROGRAM;
        };
    }

    /** The process gets only the given environment. Its process ID is also the ID of its process group. */
    public ProcessBuilder prepare(List<String> command, Map<String, String> environment) {
        return switch (operatingSystem) {
            case LINUX -> prepareWithSetsid(command, environment);
            case MACOS -> prepareWithPerl(command, environment);
        };
    }

    private static ProcessBuilder prepareWithSetsid(List<String> command, Map<String, String> environment) {
        return createBuilder(List.of(SETSID_PROGRAM, "--wait"), command, environment);
    }

    /** perl stops on a locale that the system does not have, so it starts with the locale C and restores LC_ALL. */
    private static ProcessBuilder prepareWithPerl(List<String> command, Map<String, String> environment) {
        ProcessBuilder builder = createBuilder(List.of(PERL_PROGRAM, "-e", PERL_SCRIPT, "--"), command, environment);
        String commandLocale = builder.environment().put(LOCALE_VARIABLE, SAFE_LOCALE);
        if (commandLocale != null) {
            builder.environment().put(SAVED_LOCALE_VARIABLE, commandLocale);
        }
        return builder;
    }

    private static ProcessBuilder createBuilder(List<String> launcher, List<String> command,
                                                Map<String, String> environment) {
        List<String> fullCommand = new ArrayList<>(launcher);
        fullCommand.addAll(command);
        ProcessBuilder builder = new ProcessBuilder(fullCommand);
        builder.environment().clear();
        builder.environment().putAll(environment);
        return builder;
    }
}
