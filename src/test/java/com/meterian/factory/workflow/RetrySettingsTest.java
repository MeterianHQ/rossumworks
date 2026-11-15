package com.meterian.factory.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RetrySettingsTest {

    @Test
    void readsTheDefaults() {
        RetrySettings settings = RetrySettings.fromProperties(Map.<String, String>of()::get);

        assertThat(settings).isEqualTo(new RetrySettings(2, 3)).isEqualTo(RetrySettings.defaults());
    }

    @Test
    void readsTheProperties() {
        Map<String, String> properties = Map.of("factory.retries.review-session", "4",
                "factory.retries.forward-check", "7");

        assertThat(RetrySettings.fromProperties(properties::get)).isEqualTo(new RetrySettings(4, 7));
    }

    @Test
    void refusesZero() {
        Map<String, String> properties = Map.of("factory.retries.forward-check", "0");

        assertThatThrownBy(() -> RetrySettings.fromProperties(properties::get))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The system property factory.retries.forward-check must be a whole number of 1 or more,"
                        + " but it is 0.");
    }

    @Test
    void refusesAText() {
        Map<String, String> properties = Map.of("factory.retries.review-session", "many");

        assertThatThrownBy(() -> RetrySettings.fromProperties(properties::get))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The system property factory.retries.review-session must be a whole number of 1 or more,"
                        + " but it is many.");
    }
}
