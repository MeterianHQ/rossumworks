package com.meterian.factory.workflow;

import com.meterian.factory.state.StatusComment;
import java.util.List;

/** Writes the comment of an agent question. The agent text cannot open a hidden block or fill the issue. */
final class QuestionComment {

    static final int MAXIMUM_QUESTION_LENGTH = 20_000;

    private static final String CUT_NOTE = "The question is too long; the factory cut it.";

    private QuestionComment() {
    }

    static String render(String question, List<String> allowedAccounts) {
        String mention = allowedAccounts.stream().findFirst().map(login -> "@" + login).orElse("");
        return "The agent asks this question:\n\n" + StatusComment.neutralizeCommentMarkers(cut(question))
                + "\n\n" + mention;
    }

    private static String cut(String question) {
        if (question.length() <= MAXIMUM_QUESTION_LENGTH) {
            return question;
        }
        return question.substring(0, MAXIMUM_QUESTION_LENGTH) + "\n\n" + CUT_NOTE;
    }
}
