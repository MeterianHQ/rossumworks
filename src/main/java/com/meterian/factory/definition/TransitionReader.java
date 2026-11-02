package com.meterian.factory.definition;

import com.meterian.factory.state.WaitReason;
import java.util.Locale;
import java.util.Set;

/** Reads the value of a transition. It knows the names of the loops, to tell a loop from a state. */
final class TransitionReader {

    private static final String BLOCK_WORD = "block";
    private static final Set<String> OBJECT_KEYS = Set.of("to", "keep-status", "wait", "block");

    private final Set<String> loopNames;

    TransitionReader(Set<String> loopNames) {
        this.loopNames = loopNames;
    }

    Transition read(DefinitionNode value, String owner) throws DefinitionException {
        if (value.isText()) {
            return readWord(value.asText());
        }
        if (!value.isObject()) {
            throw DefinitionNode.createFailure(owner + " has a transition that is not valid");
        }
        value.requireOnlyKeys(OBJECT_KEYS);
        if (value.containsKey("to")) {
            return new Transition.GoTo(value.requireText("to"), value.findFlag("keep-status"));
        }
        if (value.containsKey("wait")) {
            return new Transition.Wait(readReason(value.requireText("wait"), owner));
        }
        if (value.containsKey("block")) {
            return new Transition.Block(readReason(value.requireText("block"), owner));
        }
        throw DefinitionNode.createFailure(owner + " has a transition without to, wait, or block");
    }

    private Transition readWord(String word) {
        if (word.equals(BLOCK_WORD)) {
            return new Transition.Block(WaitReason.FAILURE);
        }
        if (loopNames.contains(word)) {
            return new Transition.EnterLoop(word);
        }
        return new Transition.GoTo(word, false);
    }

    private static WaitReason readReason(String name, String owner) throws DefinitionException {
        try {
            return WaitReason.valueOf(name.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            throw DefinitionNode.createFailure(owner + " has the unknown reason " + name);
        }
    }
}
