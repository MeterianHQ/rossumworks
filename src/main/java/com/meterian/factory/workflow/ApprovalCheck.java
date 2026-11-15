package com.meterian.factory.workflow;

import com.meterian.factory.state.ActionType;
import com.meterian.factory.state.IssueRecord;
import java.util.Optional;

/** The rule of the label factory:approve, for the page and for the worker. */
public final class ApprovalCheck {

    private ApprovalCheck() {
    }

    /** Only the action approval reads the label: a merge approval, or the approval of a new issue text. */
    public static boolean waitsForApproval(IssueRecord record) {
        return record.action().equals(Optional.of(ActionType.APPROVAL));
    }
}
