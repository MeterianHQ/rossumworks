package com.meterian.factory.web;

/** Sends the live link of an issue to the phone of the owner, with a comment that mentions the owner. */
public interface PhoneLinkSender {

    /**
     * Posts the comment with the link on the issue.
     *
     * @throws com.meterian.factory.github.GitHubException when GitHub refuses the comment
     */
    void sendLink(int issueNumber, String link);
}
