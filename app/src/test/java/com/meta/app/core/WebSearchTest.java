package com.meta.app.core;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WebSearchTest {

    @Test
    public void clean_stripsTagsAndCollapsesWhitespace() {
        assertEquals("hello world", WebSearch.clean("<b>hello</b>\n  world"));
        assertEquals("a & b", WebSearch.clean("a &amp; b"));
        assertEquals("", WebSearch.clean(null));
        assertEquals("", WebSearch.clean("<div></div>"));
    }

    @Test
    public void parseBingHtml_extractsTitleUrlSnippet() {
        String html = "<div>"
            + "<li class=\"b_algo\"><h2><a href=\"https://example.com/doc\">The Doc</a></h2>"
            + "<p>This is the <b>snippet</b> text.</p></li>"
            + "<li class=\"b_algo\"><h2><a href=\"https://example2.com\">Second</a></h2>"
            + "<p>Second snippet.</p></li>"
            + "</div>";
        List<WebSearch.Result> results = WebSearch.parseBingHtml(html);
        assertEquals(2, results.size());
        assertEquals("https://example.com/doc", results.get(0).url);
        assertEquals("The Doc", WebSearch.clean(results.get(0).title));
        assertTrue(results.get(0).snippet.contains("snippet"));
    }

    @Test
    public void search_rejectsEmptyQuery() {
        assertTrue(WebSearch.search("").startsWith("Error"));
        assertTrue(WebSearch.search(null).startsWith("Error"));
    }
}
