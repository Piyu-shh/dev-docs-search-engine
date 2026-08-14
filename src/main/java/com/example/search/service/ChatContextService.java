package com.example.search.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ChatContextService {

    // Session ID -> List of chat messages
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<ChatMessage>> chatContexts = new ConcurrentHashMap<>();
    private final int maxHistoryMessages;

    public ChatContextService(@Value("${chat.context.max.messages:10}") int maxHistoryMessages) {
        this.maxHistoryMessages = maxHistoryMessages;
    }

    /**
     * Add a user message to the chat context
     */
    public void addUserMessage(String sessionId, String message) {
        if (sessionId == null || message == null || message.trim().isEmpty()) {
            return;
        }
        addMessage(sessionId, new ChatMessage("user", message.trim(), System.currentTimeMillis()));
    }

    /**
     * Add an assistant response to the chat context
     */
    public void addAssistantMessage(String sessionId, String message) {
        if (sessionId == null || message == null || message.trim().isEmpty()) {
            return;
        }
        addMessage(sessionId, new ChatMessage("assistant", message.trim(), System.currentTimeMillis()));
    }

    private synchronized void addMessage(String sessionId, ChatMessage msg) {
        CopyOnWriteArrayList<ChatMessage> context = chatContexts.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        context.add(msg);
        while (context.size() > maxHistoryMessages) {
            context.remove(0);
        }
    }

    /**
     * Get the chat context for a session
     */
    public List<ChatMessage> getChatContext(String sessionId) {
        if (sessionId == null) return new ArrayList<>();
        List<ChatMessage> list = chatContexts.get(sessionId);
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    /**
     * Build a context string from chat history
     */
    public String buildContextString(String sessionId) {
        List<ChatMessage> context = getChatContext(sessionId);
        if (context.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Previous conversation:\n");
        for (ChatMessage msg : context) {
            sb.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Clear chat context for a session
     */
    public void clearContext(String sessionId) {
        if (sessionId != null) {
            chatContexts.remove(sessionId);
        }
    }

    /**
     * Check if a session has chat history
     */
    public boolean hasContext(String sessionId) {
        if (sessionId == null) return false;
        List<ChatMessage> context = chatContexts.get(sessionId);
        return context != null && !context.isEmpty();
    }

    /**
     * Get number of messages in context
     */
    public int getContextSize(String sessionId) {
        if (sessionId == null) return 0;
        List<ChatMessage> context = chatContexts.get(sessionId);
        return context != null ? context.size() : 0;
    }

    /**
     * Inner class to represent a chat message
     */
    public static class ChatMessage {
        private String role; // "user" or "assistant"
        private String content;
        private long timestamp;

        public ChatMessage(String role, String content, long timestamp) {
            this.role = role;
            this.content = content;
            this.timestamp = timestamp;
        }

        public String getRole() { return role; }
        public String getContent() { return content; }
        public long getTimestamp() { return timestamp; }

        @Override
        public String toString() {
            return "ChatMessage{" +
                    "role='" + role + '\'' +
                    ", content='" + (content != null && content.length() > 50 ? content.substring(0, 50) + "..." : content) + '\'' +
                    ", timestamp=" + timestamp +
                    '}';
        }
    }
}
