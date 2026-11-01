package com.meterian.factory.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PollRequestSummaryTest {

    @Test
    void describesThePollWithoutRequests() {
        assertThat(PollRequestSummary.describe(Map.of())).isEqualTo("The poll sent 0 GitHub requests.");
    }

    @Test
    void ordersTheCountsFromTheLargest() {
        Map<String, Integer> counts = Map.of("GET /repos/{o}/{r}/issues", 1,
                "GET /repos/{o}/{r}/issues/{n}/events", 3, "GET /repos/{o}/{r}/issues/{n}/comments (304)", 2);

        assertThat(PollRequestSummary.describe(counts)).isEqualTo("The poll sent 6 GitHub requests: "
                + "GET /repos/{o}/{r}/issues/{n}/events=3, GET /repos/{o}/{r}/issues/{n}/comments (304)=2, "
                + "GET /repos/{o}/{r}/issues=1.");
    }

    @Test
    void showsOnlyTheTenLargestCountsButTheFullTotal() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int path = 1; path <= 12; path++) {
            counts.put("GET /path" + String.format("%02d", path), path);
        }

        String line = PollRequestSummary.describe(counts);

        assertThat(line).startsWith("The poll sent 78 GitHub requests: GET /path12=12, GET /path11=11, ")
                .endsWith("GET /path03=3, and 2 more paths.")
                .doesNotContain("/path02").doesNotContain("/path01");
    }
}
