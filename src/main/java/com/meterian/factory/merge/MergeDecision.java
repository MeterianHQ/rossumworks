package com.meterian.factory.merge;

/** The answer of the merge gate. */
public sealed interface MergeDecision {

    /** All proofs pass and the risk is low and small. */
    record Automatic() implements MergeDecision {
    }

    /** All proofs pass and the owner approved the merge. */
    record Approved() implements MergeDecision {
    }

    /** All proofs pass, but the owner must approve the merge. */
    record NeedsOwner(String reason) implements MergeDecision {
    }

    /** A proof is missing or wrong. The change cannot merge. */
    record Refused(String reason) implements MergeDecision {
    }
}
