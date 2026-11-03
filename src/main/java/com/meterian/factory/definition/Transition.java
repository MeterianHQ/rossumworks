package com.meterian.factory.definition;

import com.meterian.factory.state.WaitReason;

/** The target of an outcome of a step. */
public sealed interface Transition {

    record GoTo(String state, boolean keepStatus) implements Transition {
    }

    record EnterLoop(String loop) implements Transition {
    }

    record Wait(WaitReason reason) implements Transition {
    }

    record Block(WaitReason reason) implements Transition {
    }
}
