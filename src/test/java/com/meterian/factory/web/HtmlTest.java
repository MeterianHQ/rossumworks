package com.meterian.factory.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HtmlTest {

    @Test
    void escapesTheFiveSpecialCharacters() {
        assertThat(Html.escape("<a href=\"x\" title='y'>Tom & Jerry</a>"))
                .isEqualTo("&lt;a href=&quot;x&quot; title=&#39;y&#39;&gt;Tom &amp; Jerry&lt;/a&gt;");
    }

    @Test
    void escapesAnAmpersandOnlyOnce() {
        assertThat(Html.escape("&amp;")).isEqualTo("&amp;amp;");
    }

    @Test
    void keepsPlainText() {
        assertThat(Html.escape("Plain text 123")).isEqualTo("Plain text 123");
    }

    @Test
    void aNullTextGivesAnEmptyText() {
        assertThat(Html.escape(null)).isEmpty();
    }
}
