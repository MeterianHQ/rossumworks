package com.meterian.factory.build;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/** Keeps the last lines of an output. Each kept line has a maximum length. */
final class OutputTail {

    private static final int MAXIMUM_LINES = 200;
    private static final int MAXIMUM_LINE_LENGTH = 10_000;

    private final Deque<String> lines = new ArrayDeque<>();

    void readAll(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        boolean hasContent = false;
        int character;
        while ((character = reader.read()) != -1) {
            if (character == '\n') {
                addLine(line.toString());
                line.setLength(0);
                hasContent = false;
                continue;
            }
            hasContent = true;
            if (line.length() < MAXIMUM_LINE_LENGTH) {
                line.append((char) character);
            }
        }
        if (hasContent) {
            addLine(line.toString());
        }
    }

    synchronized String text() {
        return String.join("\n", lines);
    }

    private synchronized void addLine(String line) {
        lines.addLast(line);
        if (lines.size() > MAXIMUM_LINES) {
            lines.removeFirst();
        }
    }
}
