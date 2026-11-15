package com.meterian.factory.state;

/**
 * The prompt that the next coding action gives to the agent. The findings of FIX and RESUME are in the file
 * .factory-work/pending-findings.json of the worktree.
 */
public enum PendingPrompt {
    IMPLEMENT, FIX, RESUME
}
