package com.meterian.factory.ownership;

/** Checks, just before a step changes GitHub, that the worker still owns the issue. */
public interface OwnershipGuard {

    /** Throws OwnershipLostException when another account owns the issue. */
    void verifyStillOwner(int issueNumber);

    /** A guard that never throws. */
    static OwnershipGuard none() {
        return issueNumber -> { };
    }
}
