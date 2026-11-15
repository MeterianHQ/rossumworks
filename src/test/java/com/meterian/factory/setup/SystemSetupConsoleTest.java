package com.meterian.factory.setup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SystemSetupConsoleTest {

    @Test
    void isNotATerminalWithoutAConsole() {
        assertFalse(SystemSetupConsole.isTerminal(null, ConsoleWithoutTerminalCheck.class));
    }

    @Test
    void isATerminalWhenTheConsoleTypeHasNoTerminalCheck() {
        assertTrue(SystemSetupConsole.isTerminal(new ConsoleWithoutTerminalCheck(), ConsoleWithoutTerminalCheck.class));
    }

    @Test
    void usesTheTerminalCheckWhenTheConsoleTypeHasIt() {
        assertTrue(SystemSetupConsole.isTerminal(new ConsoleWithTerminalCheck(true), ConsoleWithTerminalCheck.class));
        assertFalse(SystemSetupConsole.isTerminal(new ConsoleWithTerminalCheck(false), ConsoleWithTerminalCheck.class));
    }

    public static final class ConsoleWithoutTerminalCheck {
    }

    public static final class ConsoleWithTerminalCheck {

        private final boolean terminal;

        ConsoleWithTerminalCheck(boolean terminal) {
            this.terminal = terminal;
        }

        public boolean isTerminal() {
            return terminal;
        }
    }
}
