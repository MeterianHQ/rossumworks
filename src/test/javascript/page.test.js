"use strict";

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { FakeDocument, ELEMENT_NODE, TEXT_NODE, CARD_HEIGHT } = require("./fake-dom.js");

const PAGE_SCRIPT = path.join(__dirname, "..", "..", "main", "resources", "web", "page.js");

function renderHeader(lastCheck) {
    return "<header class=\"topbar\"><div class=\"health\"><div class=\"tile\"><span class=\"tile-label\">"
            + "Last GitHub check</span><span class=\"tile-value\">" + lastCheck + "</span></div></div>"
            + "<div class=\"factory-state state-running\"><form method=\"post\" action=\"/factory/stop\">"
            + "<button>Stop</button></form></div></header>";
}

function renderCard(issueNumber, title) {
    return "<article class=\"card\" data-key=\"" + issueNumber + "\"><h3>#" + issueNumber + " " + title + "</h3>"
            + "<div class=\"actions\"><form method=\"post\" action=\"/issues/" + issueNumber + "/approve\">"
            + "<input type=\"hidden\" name=\"fingerprint\" value=\"f" + issueNumber + "\">"
            + "<button>Approve</button></form></div></article>";
}

function renderColumn(column, cards) {
    const content = cards.length === 0 ? "<p class=\"empty\">No issues</p>" : cards.join("");
    return "<section class=\"column status-" + column + "\" data-key=\"" + column + "\"><h2>"
            + "<span class=\"count\">" + cards.length + "</span></h2><div class=\"cards\">" + content
            + "</div></section>";
}

function renderBoard(lastCheck, columns) {
    return renderHeader(lastCheck) + "<main class=\"columns\">" + columns.join("") + "</main>";
}

function renderCards(issueNumbers, title) {
    return issueNumbers.map((issueNumber) => renderCard(issueNumber, title || "Title"));
}

/** Loads page.js in a page that shows the board, with a fake fetch and a fake event stream. */
function openPage(boardHtml) {
    const document = new FakeDocument();
    const board = document.createElement("div");
    board.setAttribute("id", "board");
    board.innerHTML = boardHtml;
    document.body.appendChild(board);
    const page = { document, board, serverHtml: boardHtml, eventSources: [] };
    class FakeEventSource {
        constructor() {
            this.listeners = {};
            this.readyState = 1;
            page.eventSources.push(this);
        }

        addEventListener(type, listener) {
            this.listeners[type] = listener;
        }
    }
    FakeEventSource.CLOSED = 2;
    const fetch = () => Promise.resolve({
        status: 200,
        ok: true,
        text: () => Promise.resolve(page.serverHtml),
    });
    const window = {
        addEventListener() {},
        setTimeout() {},
        confirm: () => true,
        location: { reload() {} },
    };
    const context = vm.createContext({ document, window, fetch, EventSource: FakeEventSource,
        Node: { ELEMENT_NODE, TEXT_NODE } });
    vm.runInContext(fs.readFileSync(PAGE_SCRIPT, "utf8"), context);
    return page;
}

/** The server sends the event "board", and the page fetches the new board. */
async function sendBoardEvent(page, newBoardHtml) {
    page.serverHtml = newBoardHtml;
    page.eventSources[page.eventSources.length - 1].listeners.board();
    await new Promise((resolve) => setImmediate(resolve));
}

function findColumn(page, column) {
    return page.board.querySelectorAll("section.column").find((section) => section.getAttribute("data-key") === column);
}

function findCardList(page, column) {
    return findColumn(page, column).querySelector("div.cards");
}

function findCard(page, issueNumber) {
    return page.board.querySelectorAll("article.card")
            .find((card) => card.getAttribute("data-key") === String(issueNumber));
}

test("an event without a change touches nothing and keeps the scroll positions", async () => {
    const html = renderBoard("10:00", [renderColumn("queued", renderCards([1, 2, 3, 4, 5])),
        renderColumn("done", renderCards([6, 7, 8, 9]))]);
    const page = openPage(html);
    findCardList(page, "queued").scrollTop = 150;
    findCardList(page, "done").scrollTop = 220;
    page.board.querySelector("main.columns").scrollLeft = 40;
    const changesBefore = page.document.changes;

    await sendBoardEvent(page, html);

    assert.strictEqual(page.document.changes, changesBefore);
    assert.strictEqual(findCardList(page, "queued").scrollTop, 150);
    assert.strictEqual(findCardList(page, "done").scrollTop, 220);
    assert.strictEqual(page.board.querySelector("main.columns").scrollLeft, 40);
});

test("a change of only the header keeps the columns and the focus", async () => {
    const columns = [renderColumn("queued", renderCards([1, 2, 3]))];
    const page = openPage(renderBoard("10:00", columns));
    const main = page.board.querySelector("main.columns");
    const queued = findColumn(page, "queued");
    findCardList(page, "queued").scrollTop = 120;
    findCard(page, 2).querySelector("button").focus();

    await sendBoardEvent(page, renderBoard("10:01", columns));

    assert.match(page.board.querySelector("header.topbar").textContent, /10:01/);
    assert.strictEqual(page.board.querySelector("main.columns"), main);
    assert.strictEqual(findColumn(page, "queued"), queued);
    assert.strictEqual(findCardList(page, "queued").scrollTop, 120);
    assert.strictEqual(page.document.activeElement, findCard(page, 2).querySelector("button"));
});

test("a change of one card keeps the other cards and the scroll position", async () => {
    const page = openPage(renderBoard("10:00", [renderColumn("queued", renderCards([1, 2, 3, 4]))]));
    const firstCard = findCard(page, 1);
    const changedCard = findCard(page, 2);
    const lastCard = findCard(page, 4);
    findCardList(page, "queued").scrollTop = 130;
    const changedCards = [renderCard(1, "Title"), renderCard(2, "New title"), renderCard(3, "Title"),
        renderCard(4, "Title")];

    await sendBoardEvent(page, renderBoard("10:00", [renderColumn("queued", changedCards)]));

    assert.strictEqual(findCard(page, 1), firstCard);
    assert.strictEqual(findCard(page, 2), changedCard);
    assert.strictEqual(findCard(page, 4), lastCard);
    assert.match(findCard(page, 2).textContent, /#2 New title/);
    assert.strictEqual(findCardList(page, "queued").scrollTop, 130);
});

test("a card that comes above the view keeps the cards in view", async () => {
    const page = openPage(renderBoard("10:00", [renderColumn("queued", renderCards([2, 3, 4, 5, 6]))]));
    findCardList(page, "queued").scrollTop = 250;

    await sendBoardEvent(page, renderBoard("10:00", [renderColumn("queued", renderCards([1, 2, 3, 4, 5, 6]))]));

    assert.strictEqual(findCardList(page, "queued").scrollTop, 250 + CARD_HEIGHT);
    assert.strictEqual(findCard(page, 4).getBoundingClientRect().top, -50);
});

test("a card that goes above the view keeps the cards in view", async () => {
    const page = openPage(renderBoard("10:00", [renderColumn("queued", renderCards([1, 2, 3, 4, 5, 6]))]));
    const cardInView = findCard(page, 3);
    findCardList(page, "queued").scrollTop = 250;

    await sendBoardEvent(page, renderBoard("10:00", [renderColumn("queued", renderCards([2, 3, 4, 5, 6]))]));

    assert.strictEqual(findCardList(page, "queued").scrollTop, 250 - CARD_HEIGHT);
    assert.strictEqual(findCard(page, 3), cardInView);
    assert.strictEqual(cardInView.getBoundingClientRect().top, -50);
});

test("when the card at the top of the view goes, the next card stays in view", async () => {
    const page = openPage(renderBoard("10:00", [renderColumn("queued", renderCards([1, 2, 3, 4, 5, 6])),
        renderColumn("done", renderCards([]))]));
    const nextCard = findCard(page, 4);
    findCardList(page, "queued").scrollTop = 250;
    assert.strictEqual(nextCard.getBoundingClientRect().top, 50);

    await sendBoardEvent(page, renderBoard("10:00", [renderColumn("queued", renderCards([1, 2, 4, 5, 6])),
        renderColumn("done", renderCards([3]))]));

    assert.strictEqual(findCardList(page, "queued").scrollTop, 250 - CARD_HEIGHT);
    assert.strictEqual(findCard(page, 4), nextCard);
    assert.strictEqual(nextCard.getBoundingClientRect().top, 50);
});

test("a card at the top of a list that the user did not scroll stays visible", async () => {
    const page = openPage(renderBoard("10:00", [renderColumn("queued", renderCards([2, 3]))]));

    await sendBoardEvent(page, renderBoard("10:00", [renderColumn("queued", renderCards([1, 2, 3]))]));

    assert.strictEqual(findCardList(page, "queued").scrollTop, 0);
    assert.strictEqual(findCard(page, 1).getBoundingClientRect().top, 0);
});

test("a card that moves to another column leaves the first column and shows in the other", async () => {
    const page = openPage(renderBoard("10:00", [renderColumn("queued", renderCards([1])),
        renderColumn("done", renderCards([]))]));

    await sendBoardEvent(page, renderBoard("10:00", [renderColumn("queued", renderCards([])),
        renderColumn("done", renderCards([1]))]));

    assert.strictEqual(findCardList(page, "queued").querySelector("article.card"), null);
    assert.match(findCardList(page, "queued").textContent, /No issues/);
    assert.strictEqual(findCardList(page, "done").querySelector("article.card").getAttribute("data-key"), "1");
});

test("a busy button becomes usable after an update", async () => {
    const html = renderBoard("10:00", [renderColumn("queued", renderCards([1]))]);
    const page = openPage(html);
    const form = findCard(page, 1).querySelector("form");
    const button = form.querySelector("button");
    page.document.dispatch("submit", form);
    assert.ok(button.classList.contains("busy") && button.disabled);

    await sendBoardEvent(page, html);

    assert.strictEqual(form.querySelector("button"), button);
    assert.ok(!button.classList.contains("busy"));
    assert.ok(!button.disabled);
});

test("the board after the update has the HTML of the server", async () => {
    const page = openPage(renderBoard("10:00", [renderColumn("queued", renderCards([1, 2, 3])),
        renderColumn("done", renderCards([4]))]));
    const newHtml = renderBoard("10:05", [renderColumn("in-progress", renderCards([3])),
        renderColumn("queued", renderCards([2, 1], "Other")), renderColumn("done", renderCards([]))]);

    await sendBoardEvent(page, newHtml);

    assert.strictEqual(serialize(page.board), serialize(openPage(newHtml).board));
});

function serialize(node) {
    if (node.nodeType === TEXT_NODE) {
        return node.nodeValue;
    }
    const attributes = node.attributes.map((attribute) => " " + attribute.name + "=\"" + attribute.value + "\"");
    return "<" + node.nodeName + attributes.join("") + ">" + node.childNodes.map(serialize).join("") + "</>";
}
