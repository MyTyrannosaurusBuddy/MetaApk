package com.meta.app.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class AgentConversationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void checkpoint_restoresCompletedToolTurnsAndRequirements() throws Exception {
        File checkpoint = new File(temp.getRoot(), "session.json");
        List<LLMClient.ChatMessage> messages = conversation();
        addTurn(messages, "write-1", "source content");
        AgentConversation.save(checkpoint, messages);
        List<LLMClient.ChatMessage> restored = AgentConversation.load(checkpoint);
        assertEquals(4, restored.size());
        assertEquals("requirements", restored.get(1).content);
        assertEquals("write-1", restored.get(2).toolCalls.get(0).id);
        assertEquals("write-1", restored.get(3).toolCallId);
    }

    @Test public void compact_keepsRequirementsAndCompleteRecentToolGroups() {
        List<LLMClient.ChatMessage> messages = conversation();
        for (int i = 0; i < 30; i++) addTurn(messages, "call-" + i, "x".repeat(2000));
        AgentConversation.compact(messages, 6000);
        assertTrue(messages.get(1).content.startsWith("requirements"));
        assertTrue(messages.stream().anyMatch(m -> "call-29".equals(m.toolCallId)));
        assertTrue(AgentConversation.size(messages) <= 6000);
        java.util.Set<String> pending = new java.util.HashSet<>();
        for (LLMClient.ChatMessage message : messages) {
            if (message.toolCalls != null) for (LLMClient.ToolCall call : message.toolCalls) pending.add(call.id);
            if ("tool".equals(message.role)) assertTrue(pending.remove(message.toolCallId));
        }
        assertTrue(pending.isEmpty());
    }

    @Test public void compact_preservesOnlyMostRecentScreenshot() {
        List<LLMClient.ChatMessage> messages = conversation();
        LLMClient.ChatMessage first = LLMClient.ChatMessage.user("old screenshot");
        first.imageBase64 = "old";
        LLMClient.ChatMessage latest = LLMClient.ChatMessage.user("new screenshot");
        latest.imageBase64 = "latest";
        messages.addAll(Arrays.asList(first, latest));
        AgentConversation.compact(messages, 6000);
        assertNull(first.imageBase64);
        assertEquals("latest", latest.imageBase64);
    }

    @Test public void collectOldestForSummary_keepsGroupsPairedAndRecentTurnsIntact() {
        List<LLMClient.ChatMessage> messages = conversation();
        for (int i = 0; i < 10; i++) addTurn(messages, "call-" + i, "x".repeat(1000));
        List<LLMClient.ChatMessage> chunk = new ArrayList<>();
        int end = AgentConversation.collectOldestForSummary(messages, chunk, 8000);
        assertTrue("Nothing collected", end > 2);
        assertFalse("Chunk empty", chunk.isEmpty());
        // The chunk starts with a complete group and never contains orphans.
        java.util.Set<String> pending = new java.util.HashSet<>();
        for (LLMClient.ChatMessage message : chunk) {
            if (message.toolCalls != null) {
                for (LLMClient.ToolCall call : message.toolCalls) pending.add(call.id);
            }
            if ("tool".equals(message.role)) assertTrue(pending.remove(message.toolCallId));
        }
        assertTrue("Chunk cut through a tool group", pending.isEmpty());
        // The recent turns survive: the tail of the conversation is untouched.
        assertTrue(messages.get(messages.size() - 1).toolCallId.startsWith("call-"));
        // The collected budget stays at roughly half the limit.
        assertTrue(AgentConversation.size(chunk) <= 4000);
    }

    @Test public void collectOldestForSummary_returnsNothingForShortHistories() {
        List<LLMClient.ChatMessage> messages = conversation();
        addTurn(messages, "only", "small");
        List<LLMClient.ChatMessage> chunk = new ArrayList<>();
        int end = AgentConversation.collectOldestForSummary(messages, chunk, 8000);
        assertEquals(2, end);
        assertTrue(chunk.isEmpty());
    }

    @Test public void checkpoint_preservesFeedbackAfterSwitchOrRestart() throws Exception {
        File checkpoint = new File(temp.getRoot(), "requirements.json");
        AgentConversation.save(checkpoint, conversation(), Arrays.asList("offline mode", "keep counters"));
        assertEquals(Arrays.asList("offline mode", "keep counters"), AgentConversation.loadRequirements(checkpoint));
        assertEquals(2, AgentConversation.load(checkpoint).size());
    }

    private List<LLMClient.ChatMessage> conversation() {
        return new ArrayList<>(Arrays.asList(LLMClient.ChatMessage.system("system"),
            LLMClient.ChatMessage.user("requirements")));
    }

    private void addTurn(List<LLMClient.ChatMessage> messages, String id, String output) {
        LLMClient.ChatMessage assistant = new LLMClient.ChatMessage();
        assistant.role = "assistant";
        LLMClient.ToolCall call = new LLMClient.ToolCall();
        call.id = id; call.name = "read_file"; call.arguments = "{\"path\":\"src/A.java\"}";
        assistant.toolCalls = Arrays.asList(call);
        LLMClient.ChatMessage result = new LLMClient.ChatMessage();
        result.role = "tool"; result.toolCallId = id; result.content = output;
        messages.add(assistant); messages.add(result);
    }
}
