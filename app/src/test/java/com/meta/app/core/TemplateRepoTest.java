package com.meta.app.core;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TemplateRepoTest {

    @Test
    public void parseManifest_readsEntries() {
        String json = "{\"version\":1,\"templates\":["
            + "{\"name\":\"counter\",\"description\":\"desc\",\"path\":\"c/src/A.java\",\"keywords\":[\"tally\",\"click\"]}"
            + "]}";
        List<TemplateRepo.Template> templates = TemplateRepo.parseManifest(json);
        assertEquals(1, templates.size());
        assertEquals("counter", templates.get(0).name);
        assertEquals("c/src/A.java", templates.get(0).path);
        assertTrue(templates.get(0).keywords.contains("tally"));
    }

    @Test
    public void parseManifest_handlesGarbageAndEmpty() {
        assertTrue(TemplateRepo.parseManifest("not json {{{").isEmpty());
        assertTrue(TemplateRepo.parseManifest("{\"version\":1}").isEmpty());
    }
}
