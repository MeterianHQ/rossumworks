package com.meterian.factory.prompt;

import com.meterian.factory.state.Verdict;
import java.util.List;

/** A finding must be fixed before the merge. A note is information for the human reviewer and never blocks. */
public record ReviewResult(Verdict verdict, String treeHash, List<Finding> findings, List<Finding> notes) {
}
