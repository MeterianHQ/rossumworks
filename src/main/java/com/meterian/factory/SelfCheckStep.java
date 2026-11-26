package com.meterian.factory;

import java.nio.file.Path;

/**
 * One named step of the command self-check.
 *
 * @param name  the name in the output line of the step
 * @param check the work of the step: it throws when a part of the program does not work
 */
record SelfCheckStep(String name, Check check) {

    /** The work of a step. The folder is empty at the start, and the self-check deletes it at the end. */
    @FunctionalInterface
    interface Check {
        void run(Path folder) throws Exception;
    }
}
