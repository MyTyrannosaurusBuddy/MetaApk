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
        assertEquals(1, templates.get(0).files.size());
        assertEquals("c/src/A.java", templates.get(0).files.get(0));
        assertTrue(templates.get(0).keywords.contains("tally"));
    }

    @Test
    public void parseManifest_readsMultiFileTemplates() {
        String json = "{\"version\":1,\"templates\":["
            + "{\"name\":\"calculator\",\"description\":\"desc\","
            + "\"files\":[\"calculator/src/com/meta/plugin/DynamicPlugin.java\","
            + "\"calculator/src/com/meta/plugin/CalcEngine.java\","
            + "\"calculator/src/com/meta/plugin/CalculatorView.java\"],"
            + "\"keywords\":[\"calc\",\"math\"]}"
            + "]}";
        List<TemplateRepo.Template> templates = TemplateRepo.parseManifest(json);
        assertEquals(1, templates.size());
        assertEquals(3, templates.get(0).files.size());
        assertEquals("calculator/src/com/meta/plugin/DynamicPlugin.java",
            templates.get(0).files.get(0));
        assertEquals("calculator/src/com/meta/plugin/CalculatorView.java",
            templates.get(0).files.get(2));
    }

    @Test
    public void parseManifest_skipsEntriesWithoutFiles() {
        String json = "{\"version\":1,\"templates\":["
            + "{\"name\":\"broken\",\"description\":\"no files\"}"
            + "]}";
        List<TemplateRepo.Template> templates = TemplateRepo.parseManifest(json);
        assertEquals(1, templates.size());
        assertTrue(templates.get(0).files.isEmpty());
    }

    @Test
    public void parseManifest_readsStructuredComponents() {
        String json = "{\"version\":1,\"templates\":["
            + "{\"name\":\"clock\",\"description\":\"desc\","
            + "\"files\":[\"clock/src/com/meta/plugin/DynamicPlugin.java\"],"
            + "\"components\":[\"analog-clock\",\"alarm\",\"stopwatch\",\"timer\"]}"
            + "]}";
        List<TemplateRepo.Template> templates = TemplateRepo.parseManifest(json);
        assertEquals(1, templates.size());
        assertEquals(4, templates.get(0).components.size());
        assertEquals("stopwatch", templates.get(0).components.get(2));
    }

    @Test
    public void parseManifest_componentsOptional() {
        String json = "{\"version\":1,\"templates\":["
            + "{\"name\":\"counter\",\"path\":\"c/src/A.java\"}"
            + "]}";
        List<TemplateRepo.Template> templates = TemplateRepo.parseManifest(json);
        assertEquals(1, templates.size());
        assertTrue(templates.get(0).components.isEmpty());
    }

    @Test
    public void parseManifest_handlesGarbageAndEmpty() {
        assertTrue(TemplateRepo.parseManifest("not json {{{").isEmpty());
        assertTrue(TemplateRepo.parseManifest("{\"version\":1}").isEmpty());
    }
}
