package com.meterian.factory.prompt;

import com.meterian.factory.state.Verdict;
import java.util.List;

public record ReviewResult(Verdict verdict, String treeHash, List<Finding> findings) {
}
