package com.meterian.factory.web;

/** Adds and removes the labels of the issues. These are the only GitHub requests of the page. */
public interface LabelWriter {

    void addLabel(int issueNumber, String label);

    /** A label that is not on the issue is not an error. */
    void removeLabel(int issueNumber, String label);
}
