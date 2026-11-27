"use strict";

// A small DOM for the tests of page.js. It knows only the HTML of the board, and it has no dependency.

const ELEMENT_NODE = 1;
const TEXT_NODE = 3;
const DOCUMENT_FRAGMENT_NODE = 11;
const CARD_HEIGHT = 100;
const LIST_HEIGHT = 300;
const VOID_ELEMENTS = new Set(["input", "meta", "link", "br"]);
const ENTITIES = { "&amp;": "&", "&quot;": "\"", "&lt;": "<", "&gt;": ">", "&#39;": "'" };

class FakeNode {
    constructor(nodeType, nodeName, ownerDocument) {
        this.nodeType = nodeType;
        this.nodeName = nodeName;
        this.ownerDocument = ownerDocument;
        this.parentNode = null;
        this.childNodes = [];
    }

    get lastChild() {
        return this.childNodes[this.childNodes.length - 1] || null;
    }

    get children() {
        return this.childNodes.filter((child) => child.nodeType === ELEMENT_NODE);
    }

    get isConnected() {
        let node = this;
        while (node.parentNode !== null) {
            node = node.parentNode;
        }
        return node === this.ownerDocument.root;
    }

    get textContent() {
        return this.childNodes.map((child) => child.textContent).join("");
    }

    appendChild(child) {
        return this.insertBefore(child, null);
    }

    insertBefore(child, reference) {
        if (child.nodeType === DOCUMENT_FRAGMENT_NODE) {
            [...child.childNodes].forEach((fragmentChild) => this.insertBefore(fragmentChild, reference));
            return child;
        }
        if (child.parentNode !== null) {
            child.parentNode.removeChild(child);
        }
        const index = reference === null ? this.childNodes.length : this.childNodes.indexOf(reference);
        if (index < 0) {
            throw new Error("The reference is not a child of this node");
        }
        this.childNodes.splice(index, 0, child);
        child.parentNode = this;
        this.countChangeInDocument();
        return child;
    }

    removeChild(child) {
        const index = this.childNodes.indexOf(child);
        if (index < 0) {
            throw new Error("The node is not a child of this node");
        }
        this.blurIfFocusIsIn(child);
        this.childNodes.splice(index, 1);
        child.parentNode = null;
        this.countChangeInDocument();
        return child;
    }

    // As in a browser, a node that leaves the document loses the focus, also when insertBefore only moves it.
    blurIfFocusIsIn(child) {
        const focused = this.ownerDocument.focusedElement;
        if (focused !== null && this.isConnected && child.contains(focused)) {
            this.ownerDocument.focusedElement = null;
        }
    }

    contains(node) {
        while (node !== null && node !== this) {
            node = node.parentNode;
        }
        return node === this;
    }

    // A change of a node outside the document, for example in a parsed fragment, does not count.
    countChangeInDocument() {
        if (this.isConnected) {
            this.ownerDocument.countChange();
        }
    }

    querySelector(selector) {
        return this.querySelectorAll(selector)[0] || null;
    }

    // A selector is a tag name, a class name with a dot, or the two together, for example "article.card".
    querySelectorAll(selector) {
        const [tagName, className] = selector.split(".");
        const found = [];
        const visit = (node) => node.children.forEach((child) => {
            const tagMatches = tagName === "" || child.nodeName === tagName.toUpperCase();
            const classMatches = className === undefined || child.classList.contains(className);
            if (tagMatches && classMatches) {
                found.push(child);
            }
            visit(child);
        });
        visit(this);
        return found;
    }
}

class FakeText extends FakeNode {
    constructor(text, ownerDocument) {
        super(TEXT_NODE, "#text", ownerDocument);
        this.text = text;
    }

    get nodeValue() {
        return this.text;
    }

    set nodeValue(text) {
        this.text = text;
        this.countChangeInDocument();
    }

    get textContent() {
        return this.text;
    }
}

class FakeElement extends FakeNode {
    constructor(tagName, ownerDocument) {
        super(ELEMENT_NODE, tagName.toUpperCase(), ownerDocument);
        this.attributeValues = new Map();
        this.scrollTop = 0;
        this.scrollLeft = 0;
    }

    get attributes() {
        return [...this.attributeValues].map(([name, value]) => ({ name, value }));
    }

    getAttribute(name) {
        return this.attributeValues.has(name) ? this.attributeValues.get(name) : null;
    }

    hasAttribute(name) {
        return this.attributeValues.has(name);
    }

    setAttribute(name, value) {
        this.attributeValues.set(name, String(value));
        this.countChangeInDocument();
    }

    removeAttribute(name) {
        this.attributeValues.delete(name);
        this.countChangeInDocument();
    }

    get classList() {
        const element = this;
        const readClasses = () => (element.getAttribute("class") || "").split(" ").filter((name) => name !== "");
        return {
            contains: (name) => readClasses().includes(name),
            add: (name) => element.setAttribute("class", [...readClasses(), name].join(" ")),
            remove: (name) => element.setAttribute("class", readClasses().filter((other) => other !== name).join(" ")),
        };
    }

    get dataset() {
        return { issue: this.getAttribute("data-issue") };
    }

    get disabled() {
        return this.hasAttribute("disabled");
    }

    set disabled(disabled) {
        if (disabled) {
            this.setAttribute("disabled", "");
        } else {
            this.removeAttribute("disabled");
        }
    }

    set innerHTML(html) {
        [...this.childNodes].forEach((child) => this.removeChild(child));
        this.appendChild(this.ownerDocument.parse(html));
    }

    focus() {
        this.ownerDocument.focusedElement = this;
    }

    // Each child of a list of cards has the height CARD_HEIGHT. Every other element starts at the top.
    getBoundingClientRect() {
        const parent = this.parentNode;
        if (parent === null || !parent.classList || !parent.classList.contains("cards")) {
            return { top: 0, bottom: LIST_HEIGHT };
        }
        const top = parent.getBoundingClientRect().top + parent.children.indexOf(this) * CARD_HEIGHT
                - parent.scrollTop;
        return { top, bottom: top + CARD_HEIGHT };
    }
}

class FakeTemplate extends FakeElement {
    set innerHTML(html) {
        this.content = this.ownerDocument.parse(html);
    }
}

class FakeDocument {
    constructor() {
        this.changes = 0;
        this.focusedElement = null;
        this.listeners = [];
        this.root = new FakeNode(DOCUMENT_FRAGMENT_NODE, "#document", this);
        this.body = this.createElement("body");
        this.root.appendChild(this.body);
    }

    countChange() {
        this.changes++;
    }

    get activeElement() {
        return this.focusedElement !== null && this.focusedElement.isConnected ? this.focusedElement : this.body;
    }

    createElement(tagName) {
        return tagName === "template" ? new FakeTemplate(tagName, this) : new FakeElement(tagName, this);
    }

    getElementById(id) {
        const visit = (node) => {
            for (const child of node.children) {
                const found = child.getAttribute("id") === id ? child : visit(child);
                if (found !== null) {
                    return found;
                }
            }
            return null;
        };
        return visit(this.root);
    }

    addEventListener(type, listener) {
        this.listeners.push({ type, listener });
    }

    // Sends the event to the listeners of the document, as a browser does after the bubble.
    dispatch(type, target) {
        const event = {
            target,
            defaultPrevented: false,
            preventDefault() {
                this.defaultPrevented = true;
            },
        };
        this.listeners.filter((entry) => entry.type === type).forEach((entry) => entry.listener(event));
        return event;
    }

    // Reads the HTML of the server. The HTML must be well formed, as the HTML of the board is.
    parse(html) {
        const fragment = new FakeNode(DOCUMENT_FRAGMENT_NODE, "#document-fragment", this);
        const openElements = [fragment];
        const tokens = /<(\/?)([a-zA-Z0-9-]+)([^>]*)>|([^<]+)/g;
        let token;
        while ((token = tokens.exec(html)) !== null) {
            const [, closing, tagName, attributes, text] = token;
            const parent = openElements[openElements.length - 1];
            if (text !== undefined) {
                parent.appendChild(new FakeText(decodeEntities(text), this));
            } else if (closing) {
                openElements.pop();
            } else {
                const element = this.createElement(tagName.toLowerCase());
                readAttributes(attributes).forEach(([name, value]) => element.attributeValues.set(name, value));
                parent.appendChild(element);
                if (!VOID_ELEMENTS.has(tagName.toLowerCase())) {
                    openElements.push(element);
                }
            }
        }
        return fragment;
    }
}

function readAttributes(attributesText) {
    const attributes = /([^\s=]+)(?:="([^"]*)")?/g;
    const found = [];
    let attribute;
    while ((attribute = attributes.exec(attributesText)) !== null) {
        found.push([attribute[1], decodeEntities(attribute[2] || "")]);
    }
    return found;
}

function decodeEntities(text) {
    return text.replace(/&amp;|&quot;|&lt;|&gt;|&#39;/g, (entity) => ENTITIES[entity]);
}

module.exports = { FakeDocument, ELEMENT_NODE, TEXT_NODE, CARD_HEIGHT };
