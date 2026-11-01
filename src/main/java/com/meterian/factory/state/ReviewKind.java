package com.meterian.factory.state;

import java.util.Locale;

public enum ReviewKind {
    CORRECTNESS, SECURITY, ARCHITECTURE, QUALITY;

    public String fileName() {
        return "review-" + name().toLowerCase(Locale.ROOT) + ".json";
    }
}
