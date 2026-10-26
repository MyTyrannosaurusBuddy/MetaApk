package com.meta.app.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * App template repository for the agent: a GitHub-hosted template library
 * (served through jsdelivr - the GitHub CDN mirror is reachable where
 * raw.githubusercontent is not), read through a fixed manifest file so the
 * model looks templates up by name instead of guessing repository paths.
 *
 * Workflow: list_templates -> pick a close match -> fetch_template -> adapt
 * it to the user's requirements. Tools only accept template NAMES (never
 * URLs), which keeps the endpoint fixed and the model inside the repository.
 */
public final class TemplateRepo {

    private static final String BASE =
        "https://cdn.jsdelivr.net/gh/MyTyrannosaurusBuddy/metaapp-templates@main/";
    private static final String MANIFEST = BASE + "templates.json";

    private TemplateRepo() { }

    /** One template entry from the manifest. */
    public static final class Template {
        public final String name;
        public final String description;
        public final String path;
        public final String keywords;

        Template(String name, String description, String path, String keywords) {
            this.name = name;
            this.description = description;
            this.path = path;
            this.keywords = keywords;
        }
    }

    /** Lists the available templates; returns a formatted list or an error. */
    public static String list() {
        String manifest = WebFetch.fetch(MANIFEST);
        if (manifest == null || manifest.startsWith("Error")) {
            return manifest == null
                ? "Error: template repository unreachable" : manifest;
        }
        java.util.List<Template> templates = parseManifest(manifest);
        if (templates.isEmpty()) {
            return "No templates available in the repository.";
        }
        StringBuilder sb = new StringBuilder("Available app templates:\n");
        for (Template t : templates) {
            sb.append("- ").append(t.name).append(": ").append(t.description);
            if (!t.keywords.isEmpty()) {
                sb.append(" (keywords: ").append(t.keywords).append(')');
            }
            sb.append('\n');
        }
        sb.append("Download one with fetch_template <name>.\n");
        return sb.toString();
    }

    /** Fetches a template's source code by name. */
    public static String fetch(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Error: empty template name";
        }
        String manifest = WebFetch.fetch(MANIFEST);
        if (manifest == null || manifest.startsWith("Error")) {
            return manifest == null
                ? "Error: template repository unreachable" : manifest;
        }
        String wanted = name.trim().toLowerCase(java.util.Locale.ROOT);
        java.util.List<Template> templates = parseManifest(manifest);
        for (Template t : templates) {
            if (t.name.toLowerCase(java.util.Locale.ROOT).equals(wanted)) {
                String source = WebFetch.fetch(BASE + t.path);
                if (source == null || source.startsWith("Error")) {
                    return source == null
                        ? "Error: template download failed" : source;
                }
                return "Template " + t.name + " (" + t.path + "):\n\n" + source;
            }
        }
        StringBuilder available = new StringBuilder();
        for (Template t : templates) {
            available.append(t.name).append(' ');
        }
        return "Error: unknown template '" + name + "'. Available: " + available.toString().trim();
    }

    /** Parses the manifest JSON into template entries (package-visible for tests). */
    static java.util.List<Template> parseManifest(String manifestJson) {
        java.util.List<Template> out = new java.util.ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(manifestJson).getAsJsonObject();
            JsonArray array = root.getAsJsonArray("templates");
            if (array == null) {
                return out;
            }
            for (JsonElement element : array) {
                JsonObject item = element.getAsJsonObject();
                out.add(new Template(
                    item.has("name") ? item.get("name").getAsString() : "",
                    item.has("description") ? item.get("description").getAsString() : "",
                    item.has("path") ? item.get("path").getAsString() : "",
                    item.has("keywords") ? item.get("keywords").toString()
                        .replace("\"", "").replace("[", "").replace("]", "") : ""));
            }
        } catch (Exception e) {
            // Malformed manifest: no templates.
        }
        return out;
    }
}
