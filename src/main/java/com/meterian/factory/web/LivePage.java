package com.meterian.factory.web;

/** The HTML of the wait page of Browser. It has no inline script and no inline style. */
final class LivePage {

    private LivePage() {
    }

    static String render(int issueNumber) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="color-scheme" content="dark">
                <title>Claude AI Factory — #%1$d</title>
                <link rel="stylesheet" href="/page.css">
                </head>
                <body class="login-page">
                <main class="login" data-issue="%1$d"><div class="login-box">
                <div class="live-spinner" aria-hidden="true"></div>
                <p class="message" id="live-title">The live conversation of #%1$d opens…</p>
                <p class="login-subtitle" id="live-detail">This tab goes to the conversation when it is ready.</p>
                <p class="login-subtitle">Usually in a few seconds. After the current step, if the issue is in work.</p>
                <p><a href="/">Back to the board</a></p>
                </div></main>
                <script src="/live.js"></script>
                </body>
                </html>
                """.formatted(issueNumber);
    }
}
