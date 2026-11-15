package com.meterian.factory.state;

/** The reason why the record of an issue passes to another factory or to a later step. */
public enum HandOver {

    /** This factory stopped because another account owns the issue. */
    RELEASED,

    /** The next PREPARE makes the worktree from the branch on GitHub. */
    CONTINUES_PUSHED_BRANCH
}
