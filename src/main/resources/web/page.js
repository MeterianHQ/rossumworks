"use strict";

// Updates the board when the worker sends the event "board". The forms work also without this script.
(function () {
    var RECONNECT_DELAY_MILLISECONDS = 5000;

    // Only the nodes that differ from the server HTML change, so the other nodes keep their scroll and focus.
    function updateBoard(html) {
        var board = document.getElementById("board");
        var cardsInView = findCardsInView(board);
        updateChildren(board, parseHtml(html));
        cardsInView.forEach(keepCardInView);
    }

    function parseHtml(html) {
        var template = document.createElement("template");
        template.innerHTML = html;
        return template.content;
    }

    function updateChildren(current, wanted) {
        removeKeyedChildrenNotWanted(current, wanted);
        var currentByKey = mapKeyedChildren(current);
        var wantedChildren = Array.from(wanted.childNodes);
        wantedChildren.forEach(function (wantedChild, index) {
            var match = findMatchingChild(current, wantedChild, index, currentByKey);
            if (match === null) {
                current.insertBefore(wantedChild, current.childNodes[index] || null);
                return;
            }
            if (readKey(match) === null) {
                removeUnkeyedChildrenBefore(current, index, match);
            }
            if (match !== current.childNodes[index]) {
                current.insertBefore(match, current.childNodes[index]);
            }
            updateNode(match, wantedChild);
        });
        while (current.childNodes.length > wantedChildren.length) {
            current.removeChild(current.lastChild);
        }
    }

    // A column and a card have the attribute data-key. A node without a key matches by its tag.
    function readKey(node) {
        return node.nodeType === Node.ELEMENT_NODE ? node.getAttribute("data-key") : null;
    }

    function readClass(node) {
        return node.nodeType === Node.ELEMENT_NODE ? node.getAttribute("class") : null;
    }

    // A removal before the loop keeps the other children in place, so that they do not move.
    function removeKeyedChildrenNotWanted(current, wanted) {
        var wantedKeys = Array.from(wanted.childNodes).map(readKey).filter(function (key) {
            return key !== null;
        });
        Array.from(current.childNodes).forEach(function (child) {
            var key = readKey(child);
            if (key !== null && wantedKeys.indexOf(key) < 0) {
                current.removeChild(child);
            }
        });
    }

    function mapKeyedChildren(parent) {
        var childrenByKey = new Map();
        Array.from(parent.childNodes).forEach(function (child) {
            var key = readKey(child);
            if (key !== null) {
                childrenByKey.set(key, child);
            }
        });
        return childrenByKey;
    }

    function findMatchingChild(current, wantedChild, index, currentByKey) {
        var key = readKey(wantedChild);
        if (key !== null) {
            var keyedChild = currentByKey.get(key) || null;
            return keyedChild !== null && keyedChild.nodeName === wantedChild.nodeName ? keyedChild : null;
        }
        var reference = current.childNodes[index] || null;
        if (reference !== null && readKey(reference) === null && reference.nodeName === wantedChild.nodeName) {
            return reference;
        }
        return findSameChildLater(current, wantedChild, index);
    }

    // When a warning or a part of a card goes away, the later nodes with the same tag and class stay.
    function findSameChildLater(current, wantedChild, index) {
        return Array.from(current.childNodes).slice(index + 1).find(function (child) {
            return readKey(child) === null && child.nodeName === wantedChild.nodeName
                    && readClass(child) === readClass(wantedChild);
        }) || null;
    }

    // The nodes before the match have no place in the new board, so the match does not move.
    function removeUnkeyedChildrenBefore(current, index, match) {
        var children = Array.from(current.childNodes);
        children.slice(index, children.indexOf(match)).filter(function (child) {
            return readKey(child) === null;
        }).forEach(function (child) {
            current.removeChild(child);
        });
    }

    function updateNode(current, wanted) {
        if (current.nodeType !== Node.ELEMENT_NODE) {
            if (current.nodeValue !== wanted.nodeValue) {
                current.nodeValue = wanted.nodeValue;
            }
            return;
        }
        updateAttributes(current, wanted);
        updateChildren(current, wanted);
    }

    // The server never sends the class "busy", so this also removes the busy state that showBusyButton adds.
    function updateAttributes(current, wanted) {
        Array.from(current.attributes).forEach(function (attribute) {
            if (!wanted.hasAttribute(attribute.name)) {
                current.removeAttribute(attribute.name);
            }
        });
        Array.from(wanted.attributes).forEach(function (attribute) {
            if (current.getAttribute(attribute.name) !== attribute.value) {
                current.setAttribute(attribute.name, attribute.value);
            }
        });
    }

    // For each list of cards that the user scrolled, the cards in view in order, with their distance to the top.
    function findCardsInView(board) {
        return Array.from(board.querySelectorAll(".cards")).filter(function (list) {
            return list.scrollTop > 0;
        }).map(function (list) {
            var listBox = list.getBoundingClientRect();
            var cards = Array.from(list.children).filter(function (child) {
                var childBox = child.getBoundingClientRect();
                return childBox.bottom > listBox.top && childBox.top < listBox.bottom;
            }).map(function (child) {
                return { card: child, distance: child.getBoundingClientRect().top - listBox.top };
            });
            return { list: list, cards: cards };
        });
    }

    // The first card in view that is still in the list keeps its distance to the top, also when others go.
    function keepCardInView(listInView) {
        var cardInView = listInView.cards.find(function (candidate) {
            return candidate.card.parentNode === listInView.list;
        });
        if (!listInView.list.isConnected || cardInView === undefined) {
            return;
        }
        var distance = cardInView.card.getBoundingClientRect().top - listInView.list.getBoundingClientRect().top;
        listInView.list.scrollTop += distance - cardInView.distance;
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
                    updateBoard(html);
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

    function findConfirmQuestion(form) {
        if (!form.classList) {
            return null;
        }
        if (form.classList.contains("confirm-block")) {
            return "Block issue #" + form.dataset.issue + "? The running claude process stops.";
        }
        if (form.classList.contains("confirm-start-again")) {
            return "Start issue #" + form.dataset.issue + " again? The factory discards the earlier work and "
                + "runs the issue from the start in a new session.";
        }
        return null;
    }

    // The board changes its content, so the listener is on the document and finds the form by its class.
    function confirmAction(event) {
        var question = findConfirmQuestion(event.target);
        if (question && !window.confirm(question)) {
            event.preventDefault();
        }
    }

    // The button shows that the click arrived, and a second click is not possible. The button has no name,
    // so a disabled button does not change the form data. A form that opens a new tab keeps this tab usable:
    // the new tab shows the wait.
    function showBusyButton(event) {
        if (event.defaultPrevented || event.target.getAttribute("target") === "_blank") {
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

    // The order matters: showBusyButton must see the result of the confirm.
    document.addEventListener("submit", confirmAction);
    document.addEventListener("submit", showBusyButton);
    window.addEventListener("pageshow", refreshBoardFromHistory);
    openEvents();
})();
