package com.meterian.factory.web.login;

import com.meterian.factory.web.Html;

/** The HTML of the login form. It has no inline script and no inline style. */
final class LoginPage {

    private LoginPage() {
    }

    static String render(String message) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="color-scheme" content="dark">
                <title>Factory login</title>
                <link rel="stylesheet" href="/page.css">
                </head>
                <body class="login-page">
                <main class="login"><div class="login-box">
                <div class="login-mark" aria-hidden="true"></div>
                <h1>Claude AI Factory</h1>
                <p class="login-subtitle">Log in to see the board of the factory.</p>
                %s<form method="post" action="/login">
                <label for="password">Password</label>
                <input id="password" type="password" name="password" autocomplete="current-password" required autofocus>
                <button type="submit">Log in</button>
                </form>
                </div></main>
                </body>
                </html>
                """.formatted(renderMessage(message));
    }

    private static String renderMessage(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        return "<p class=\"login-message\">" + Html.escape(message) + "</p>\n";
    }
}
