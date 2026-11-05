package com.meterian.factory.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.definition.ColumnNames;
import org.junit.jupiter.api.Test;

class ColumnLabelsTest {

    private static final int GITHUB_LABEL_MAXIMUM_LENGTH = 50;

    @Test
    void theLabelOfTheLongestColumnFitsInAGitHubLabel() {
        String longestColumn = "a".repeat(ColumnNames.MAXIMUM_LENGTH);

        assertThat(ColumnLabels.labelName(longestColumn)).hasSize(GITHUB_LABEL_MAXIMUM_LENGTH);
    }
}
