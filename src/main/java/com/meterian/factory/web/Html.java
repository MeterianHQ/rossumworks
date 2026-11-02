package com.meterian.factory.web;

/** Makes text safe to write in HTML, in an element or in a quoted attribute. */
public final class Html {

    private Html() {
    }

    /** Changes the five special characters to entities. A null text gives an empty text. */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(text.length() + 16);
        for (int index = 0; index < text.length(); index++) {
            appendEscaped(escaped, text.charAt(index));
        }
        return escaped.toString();
    }

    private static void appendEscaped(StringBuilder escaped, char character) {
        switch (character) {
            case '&' -> escaped.append("&amp;");
            case '<' -> escaped.append("&lt;");
            case '>' -> escaped.append("&gt;");
            case '"' -> escaped.append("&quot;");
            case '\'' -> escaped.append("&#39;");
            default -> escaped.append(character);
        }
    }
}
