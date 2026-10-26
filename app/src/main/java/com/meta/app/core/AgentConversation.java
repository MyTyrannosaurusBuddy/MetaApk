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
        JsonObject state = new JsonObject();
        state.add("messages", GSON.toJsonTree(messages));
        state.add("requirements", GSON.toJsonTree(requirements));
        Workspace.atomicWrite(file, GSON.toJson(state));
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
                if (foundImage) message.imageBase64 = null;
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
