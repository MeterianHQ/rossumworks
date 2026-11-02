package com.meterian.factory.web;

/** Adds one label to one issue. This is the only GitHub request of the page. */
@FunctionalInterface
public interface LabelAdder {

    void addLabel(int issueNumber, String label);
}
