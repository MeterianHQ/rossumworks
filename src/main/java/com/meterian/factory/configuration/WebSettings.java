package com.meterian.factory.configuration;

import com.meterian.factory.web.login.PasswordHash;
import java.util.Optional;

/** Where the page listens. Without a password hash, the page has no login. */
public record WebSettings(String address, int port, Optional<PasswordHash> passwordHash) {

    public static final String DEFAULT_ADDRESS = "127.0.0.1";
    public static final int DEFAULT_PORT = 8090;
}
