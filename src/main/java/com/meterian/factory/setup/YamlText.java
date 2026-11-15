package com.meterian.factory.setup;

/** Writes a text value as a YAML double-quoted string. */
final class YamlText {

    private YamlText() {
    }

    static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
