package com.meterian.factory.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FactoryVersionTest {

    @Test
    void readsTheProjectVersionFromTheResource() {
        assertThat(FactoryVersion.current()).matches("\\d+\\.\\d+\\.\\d+.*");
    }
}
