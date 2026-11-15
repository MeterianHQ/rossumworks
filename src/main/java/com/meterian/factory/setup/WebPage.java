package com.meterian.factory.setup;

/** The page of the worker: where it listens and the hash of the password. */
public record WebPage(String address, int port, String passwordHash) {
}
