package com.meterian.factory.agent;

/** A claude session did not use the credential of the provider. The worker stops the factory. */
public class CredentialMismatchException extends Exception {

    public CredentialMismatchException(String message) {
        super(message);
    }
}
