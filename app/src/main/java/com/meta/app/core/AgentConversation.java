package com.meta.app.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Durable, bounded tool history. Only completed tool groups are checkpointed. */
final class AgentConversation {
    /**
     * Context budget before compaction, in characters. 256KB ~ 64K tokens:
     * comfortably inside the modern free-model contexts (128K+) while still
     * leaving room for screenshots (base64 counts 4 chars per byte) and the
     * response. The summarization triggers at 80% of this.
     */
    static final int MAX_CHARS = 256 * 1024;

    /**
     * Per-tool-result budget at the conversation entry point. Tool output is
     * an observation of an authoritative source (workspace files, the live UI,
     * the network) that the model can re-read on demand, so a long result is
     * kept as head + tail with a pointer instead of verbatim. One rule for
     * every tool - no per-tool or per-content-shape special cases.
     */
    static final int TOOL_RESULT_MAX_CHARS = 8 * 1024;
    private static final int TOOL_RESULT_TAIL_CHARS = 2 * 1024;

    private static final Gson GSON = new Gson();

    static List<LLMClient.ChatMessage> load(File file) {
        if (!file.isFile()) return new ArrayList<>();
        try {
            List<LLMClient.ChatMessage> messages = GSON.fromJson(
                readMessages(file),
                new TypeToken<List<LLMClient.ChatMessage>>() {}.getType());
            if (messages == null || messages.size() < 2
                    || !"system".equals(messages.get(0).role)
                    || !"user".equals(messages.get(1).role)) return new ArrayList<>();
            return messages;
        } catch (IOException | RuntimeException e) {
            return new ArrayList<>();
        }
    }

    static void save(File file, List<LLMClient.ChatMessage> messages) throws IOException {
        save(file, messages, loadRequirements(file));
    }

    static void save(File file, List<LLMClient.ChatMessage> messages, List<String> requirements) throws IOException {
        // Streamed write: large conversations (multi-MB JSON with base64
        // images) used to be built as THREE full copies in memory
        // (toJsonTree -> toJson String -> getBytes), which OOM'd a 256MB
        // heap mid-session. JsonWriter streams straight to the file.
        Workspace.atomicWrite(file, out -> {
            java.io.Writer sink = new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8);
            com.google.gson.stream.JsonWriter w = new com.google.gson.stream.JsonWriter(sink);
            w.beginObject();
            w.name("messages");
            w.beginArray();
            for (LLMClient.ChatMessage m : messages) {
                w.beginObject();
                if (m.role != null) w.name("role").value(m.role);
                if (m.content != null) w.name("content").value(m.content);
                if (m.imageBase64 != null) w.name("imageBase64").value(m.imageBase64);
                if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
                    w.name("toolCalls");
                    w.beginArray();
                    for (LLMClient.ToolCall call : m.toolCalls) {
                        w.beginObject();
                        if (call.id != null) w.name("id").value(call.id);
                        if (call.name != null) w.name("name").value(call.name);
                        if (call.arguments != null) w.name("arguments").value(call.arguments);
                        w.endObject();
                    }
                    w.endArray();
                }
                if (m.toolCallId != null) w.name("toolCallId").value(m.toolCallId);
                if (m.name != null) w.name("name").value(m.name);
                w.endObject();
            }
            w.endArray();
            w.name("requirements");
            w.beginArray();
            if (requirements != null) {
                for (String r : requirements) {
                    w.value(r);
                }
            }
            w.endArray();
            w.endObject();
            w.flush();
        });
    }

    private static JsonElement readMessages(File file) throws IOException {
        JsonElement json = JsonParser.parseString(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        return json.isJsonArray() ? json : json.getAsJsonObject().get("messages");
    }

    static List<String> loadRequirements(File file) {
        try {
            JsonElement json = JsonParser.parseString(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            if (!json.isJsonObject()) return new ArrayList<>();
            List<String> result = GSON.fromJson(json.getAsJsonObject().get("requirements"),
                new TypeToken<List<String>>() {}.getType());
            return result == null ? new ArrayList<>() : result;
        } catch (IOException | RuntimeException e) {
            return new ArrayList<>();
        }
    }

    /**
     * Applies the per-result budget at the single point where tool output
     * enters the conversation. Long output is truncated to a head + tail
     * preview with a pointer to the authoritative source; small results pass
     * through untouched. Mirrors how mature agent harnesses treat raw tool
     * output (preview in context, full content re-fetchable on demand).
     */
    static String boundToolResult(String result) {
        if (result == null || result.length() <= TOOL_RESULT_MAX_CHARS) return result;
        int headLen = TOOL_RESULT_MAX_CHARS - TOOL_RESULT_TAIL_CHARS;
        String head = result.substring(0, headLen);
        String tail = result.substring(result.length() - TOOL_RESULT_TAIL_CHARS);
        return head + "\n... [tool output truncated: "
            + (result.length() - headLen - TOOL_RESULT_TAIL_CHARS)
            + " chars omitted. The source of truth is still there - inspect it"
            + " again (read_file with start_line/line_count, grep, inspect_ui)"
            + " for the omitted part.] ...\n" + tail;
    }

    static int size(List<LLMClient.ChatMessage> messages) {
        int size = 0;
        for (LLMClient.ChatMessage m : messages) {
            size += messageSize(m);
        }
        return size;
    }

    private static int messageSize(LLMClient.ChatMessage m) {
        int size = 0;
        if (m.content != null) size += m.content.length();
        if (m.toolCalls != null) for (LLMClient.ToolCall call : m.toolCalls) {
            if (call.arguments != null) size += call.arguments.length();
        }
        if (m.imageBase64 != null) size += m.imageBase64.length();
        return size;
    }

    /**
     * Collects the oldest complete tool groups (an assistant call plus ALL its
     * tool results) up to roughly half the budget. The engine hands this chunk
     * to the model for summarization and replaces it with the summary - the
     * semantic continuity survives the compaction instead of being dropped.
     * The trailing messages (the recent turns) are never collected.
     *
     * @return the end index (exclusive) of the collected range; <= 2 means
     *         nothing was collected.
     */
    static int collectOldestForSummary(List<LLMClient.ChatMessage> messages,
                                       List<LLMClient.ChatMessage> out, int maxChars) {
        int budget = maxChars / 2;
        int collected = 0;
        int index = 2;
        while (index < messages.size() - 4) {
            int end = index + 1;
            while (end < messages.size() && "tool".equals(messages.get(end).role)) end++;
            if (end <= index || "tool".equals(messages.get(index).role)) {
                index++;
                continue;
            }
            int groupSize = 0;
            for (int i = index; i < end; i++) groupSize += messageSize(messages.get(i));
            if (collected > 0 && collected + groupSize > budget) {
                break;
            }
            out.addAll(messages.subList(index, end));
            collected += groupSize;
            index = end;
        }
        return index;
    }

    static void compact(List<LLMClient.ChatMessage> messages, int maxChars) {
        // A single recent image supplies visual context without accumulating bitmaps.
        boolean foundImage = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            LLMClient.ChatMessage message = messages.get(i);
            if (message.imageBase64 != null) {
                if (foundImage) {
                    message.imageBase64 = null;
                    // Keep the ORIGINAL caption: it says where the image came
                    // from and, for web_fetch references, the on-disk path to
                    // re-view it with read_file. Only append the drop note so
                    // the model knows the visual itself is gone.
                    message.content = (message.content == null
                        ? "" : message.content)
                        + "\n[image dropped from context to save space"
                        + " - re-view with read_file if a workspace path was"
                        + " given above, or inspect_ui for live UI]";
                }
                foundImage = true;
            }
        }
        if (size(messages) > maxChars) {
            LLMClient.ChatMessage requirements = messages.get(1);
            String note = "\n[Earlier tool output was compacted. Workspace files are authoritative; "
                + "inspect them before editing. Keep project decisions in PROJECT.md.]";
            if (!requirements.content.contains("[Earlier tool output was compacted.")) requirements.content += note;
        }
        // The first two messages always carry system rules and current requirements.
        while (size(messages) > maxChars && messages.size() > 2) {
            int end = 3;
            // Remove an assistant call and ALL its tool results together.
            while (end < messages.size() && "tool".equals(messages.get(end).role)) end++;
            messages.subList(2, end).clear();
        }
    }
}
