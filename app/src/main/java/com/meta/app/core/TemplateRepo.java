package com.meta.app.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;

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
        /** Repository-relative paths of ALL source files, in build order
         *  (entry file first). Legacy single-file templates carry one path. */
        public final List<String> files;
        /** Structured sub-features this template ships (e.g. [analog-clock,
         *  alarm, stopwatch, timer]) - a machine-readable claim of what the
         *  code contains, not prose the model has to interpret. */
        public final List<String> components;
        public final String keywords;

        Template(String name, String description, List<String> files,
                 List<String> components, String keywords) {
            this.name = name;
            this.description = description;
            this.files = files;
            this.components = components;
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
            if (!t.components.isEmpty()) {
                sb.append(" Components: [").append(String.join(", ", t.components)).append(']');
            }
            if (!t.keywords.isEmpty()) {
                sb.append(" (keywords: ").append(t.keywords).append(')');
            }
            sb.append('\n');
        }
        sb.append("Download one with fetch_template <name>.\n");
        return sb.toString();
    }

    /** Fetches a template's source code by name; multi-file templates are
     *  concatenated in file order, each prefixed with its path so the model
     *  can write the files separately. */
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
                if (t.files.isEmpty()) {
                    return "Error: template '" + t.name + "' has no files listed";
                }
                StringBuilder sb = new StringBuilder("Template " + t.name + ":\n");
                for (String path : t.files) {
                    String source = WebFetch.fetch(BASE + path);
                    if (source == null || source.startsWith("Error")) {
                        return source == null
                            ? "Error: template download failed for " + path : source;
                    }
                    sb.append("\n=== ").append(path).append(" ===\n\n").append(source).append('\n');
                }
                return sb.toString();
            }
        }
        StringBuilder available = new StringBuilder();
        for (Template t : templates) {
            available.append(t.name).append(' ');
        }
        return "Error: unknown template '" + name + "'. Available: " + available.toString().trim();
    }

    /** Reads a JSON string array field; empty list when absent. */
    private static List<String> stringArray(JsonObject item, String field) {
        List<String> out = new java.util.ArrayList<>();
        if (!item.has(field) || item.get(field).isJsonNull()) {
            return out;
        }
        JsonArray arr = item.getAsJsonArray(field);
        for (JsonElement e : arr) {
            String s = e.getAsString();
            if (s != null && !s.trim().isEmpty()) {
                out.add(s.trim());
            }
        }
        return out;
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
                List<String> files = new java.util.ArrayList<>();
                // Multi-file templates list "files"; the legacy single-file
                // manifest uses "path" (kept compatible).
                if (item.has("files") && !item.get("files").isJsonNull()) {
                    JsonArray arr = item.getAsJsonArray("files");
                    for (JsonElement f : arr) {
                        String s = f.getAsString();
                        if (s != null && !s.trim().isEmpty()) {
                            files.add(s.trim());
                        }
                    }
                } else if (item.has("path") && !item.get("path").isJsonNull()) {
                    String s = item.get("path").getAsString();
                    if (s != null && !s.trim().isEmpty()) {
                        files.add(s.trim());
                    }
                }
                out.add(new Template(
                    item.has("name") ? item.get("name").getAsString() : "",
                    item.has("description") ? item.get("description").getAsString() : "",
                    files,
                    stringArray(item, "components"),
                    item.has("keywords") ? item.get("keywords").toString()
                        .replace("\"", "").replace("[", "").replace("]", "") : ""));
            }
        } catch (Exception e) {
            // Malformed manifest: no templates.
        }
        return out;
    }
}
