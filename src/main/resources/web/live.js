"use strict";

// The wait page of Browser. It asks for the link of the live conversation, and then it goes to the link.
(function () {
    var POLL_INTERVAL_MILLISECONDS = 1000;
    var GIVE_UP_MILLISECONDS = 10 * 60 * 1000;
    var REMOTE_CONTROL_LINK = /^https:\/\/claude\.ai\/code\/session_[A-Za-z0-9]+$/;
    var GIVE_UP_MESSAGE = "The conversation did not open. Look at the card on the board.";

    var issueNumber = document.querySelector("main").getAttribute("data-issue");
    var startedAt = Date.now();

    function giveUp() {
        document.getElementById("live-title").textContent = GIVE_UP_MESSAGE;
        document.getElementById("live-detail").textContent = "";
        document.querySelector(".live-spinner").hidden = true;
    }

    function waitAndAsk() {
        if (Date.now() - startedAt >= GIVE_UP_MILLISECONDS) {
            giveUp();
            return;
        }
        window.setTimeout(askForLink, POLL_INTERVAL_MILLISECONDS);
    }

    function goTo(link) {
        if (REMOTE_CONTROL_LINK.test(link)) {
            window.location.href = link;
        } else {
            waitAndAsk();
        }
    }

    // A 401 reloads the page, so that the browser shows the login.
    function handleAnswer(response) {
        if (response.status === 401) {
            window.location.reload();
        } else if (response.status === 200) {
            response.json().then(function (answer) {
                goTo(String(answer.link));
            });
        } else {
            waitAndAsk();
        }
    }

    function askForLink() {
        fetch("/live/" + issueNumber + "/link", { credentials: "same-origin", cache: "no-store" })
            .then(handleAnswer)
            .catch(waitAndAsk);
    }

    askForLink();
})();
