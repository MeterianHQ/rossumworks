package com.meterian.factory.workflow;

import com.meterian.factory.prompt.Finding;
import com.meterian.factory.state.ReviewKind;

/** A note of one review: information for the human reviewer that does not block the merge. */
record ReviewNote(ReviewKind kind, Finding note) {
}
