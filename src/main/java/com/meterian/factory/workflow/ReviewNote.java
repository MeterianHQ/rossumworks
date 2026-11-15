package com.meterian.factory.workflow;

import com.meterian.factory.prompt.Finding;

/** A note of one review: information for the human reviewer that does not block the merge. */
record ReviewNote(String reviewName, Finding note) {
}
