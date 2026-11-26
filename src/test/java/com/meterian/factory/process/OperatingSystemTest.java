package com.meterian.factory.process;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class OperatingSystemTest {

    @Test
    void readsMacosFromTheNameThatJavaGives() {
        assertThat(OperatingSystem.parseName("Mac OS X")).isEqualTo(OperatingSystem.MACOS);
    }

    @Test
    void readsLinuxFromTheNameThatJavaGives() {
        assertThat(OperatingSystem.parseName("Linux")).isEqualTo(OperatingSystem.LINUX);
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void detectsMacosOnAMac() {
        assertThat(OperatingSystem.detect()).isEqualTo(OperatingSystem.MACOS);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void detectsLinuxOnALinuxMachine() {
        assertThat(OperatingSystem.detect()).isEqualTo(OperatingSystem.LINUX);
    }
}
