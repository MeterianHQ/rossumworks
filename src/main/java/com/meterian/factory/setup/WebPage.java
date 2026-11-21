package com.meterian.factory.setup;

import java.util.Optional;

/** The page of the worker: where it listens and the hash of the password. Without a hash, it has no login. */
public record WebPage(String address, int port, Optional<String> passwordHash) {
}
