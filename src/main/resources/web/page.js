"use strict";

// Updates the board when the worker sends the event "board". The forms work also without this script.
(function () {
    var RECONNECT_DELAY_MILLISECONDS = 5000;

    function replaceBoard(html) {
        document.getElementById("board").innerHTML = html;
    }

    // A 401 reloads the page, so that the browser shows the login.
    function refreshBoard() {
        fetch("/board", { credentials: "same-origin", cache: "no-store" })
            .then(function (response) {
                if (response.status === 401) {
                    window.location.reload();
                    return null;
                }
                return response.ok ? response.text() : null;
            })
            .then(function (html) {
                if (html !== null) {
                    replaceBoard(html);
                }
            })
            .catch(function () {
                return null;
            });
    }

    // The browser stops an event stream after an answer that is not 200, for example 401.
    function openEvents() {
        var events = new EventSource("/events");
        events.addEventListener("open", refreshBoard);
        events.addEventListener("board", refreshBoard);
        events.addEventListener("error", function () {
            if (events.readyState === EventSource.CLOSED) {
                refreshBoard();
                window.setTimeout(openEvents, RECONNECT_DELAY_MILLISECONDS);
            }
        });
    }

    // The board changes its content, so the listener is on the document and finds the form by its class.
    function confirmBlock(event) {
        var form = event.target;
        if (!form.classList || !form.classList.contains("confirm-block")) {
            return;
        }
        var question = "Block issue #" + form.dataset.issue + "? The running claude process stops.";
        if (!window.confirm(question)) {
            event.preventDefault();
        }
    }

    // The button shows that the click arrived, and a second click is not possible. The button has no name,
    // so a disabled button does not change the form data.
    function showBusyButton(event) {
        if (event.defaultPrevented) {
            return;
        }
        var button = event.target.querySelector("button");
        if (button) {
            button.classList.add("busy");
            button.disabled = true;
        }
    }

    // A page from the history of the browser shows the board again, without the busy buttons.
    function refreshBoardFromHistory(event) {
        if (event.persisted) {
            refreshBoard();
        }
    }

    // The order matters: showBusyButton must see the result of the confirm of Block.
    document.addEventListener("submit", confirmBlock);
    document.addEventListener("submit", showBusyButton);
    window.addEventListener("pageshow", refreshBoardFromHistory);
    openEvents();
})();
