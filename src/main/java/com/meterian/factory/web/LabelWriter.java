package com.meterian.factory.web;

/** Adds the labels of the issues. These are the only GitHub requests of the page. */
public interface LabelWriter {

    void addLabel(int issueNumber, String label);
}
