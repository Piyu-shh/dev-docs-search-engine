package com.example.search.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatContextService {

    // Session ID → List of chat messages
    private final ConcurrentHashMap<String, List<ChatMessage>> chatContexts = new ConcurrentHashMap<>();

    private static final int MAX_HISTORY_MESSAGES = 10; // Keep last 10 messages

    /**
     * Add a user message to the chat context
     */
    public void addUserMessage(String sessionId, String message) {
        List<ChatMessage> context = chatContexts.computeIfAbsent(sessionId, k -> new ArrayList<>());
        context.add(new ChatMessage("user", message, System.currentTimeMillis()));

        // Trim old messages
        if (context.size() > MAX_HISTORY_MESSAGES) {
            context = new ArrayList<>(context.subList(context.size() - MAX_HISTORY_MESSAGES, context.size()));
            chatContexts.put(sessionId, context);
        }
    }

    /**
     * Add an assistant (Gemma) response to the chat context
     */
    public void addAssistantMessage(String sessionId, String message) {
        List<ChatMessage> context = chatContexts.computeIfAbsent(sessionId, k -> new ArrayList<>());
        context.add(new ChatMessage("assistant", message, System.currentTimeMillis()));

        // Trim old messages
        if (context.size() > MAX_HISTORY_MESSAGES) {
            context = new ArrayList<>(context.subList(context.size() - MAX_HISTORY_MESSAGES, context.size()));
            chatContexts.put(sessionId, context);
        }
    }

    /**
     * Get the chat context for a session
     */
    public List<ChatMessage> getChatContext(String sessionId) {
        return chatContexts.getOrDefault(sessionId, new ArrayList<>());
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
        sb.append("\nCurrent question: ");
        return sb.toString();
    }

    /**
     * Clear chat context for a session
     */
    public void clearContext(String sessionId) {
        chatContexts.remove(sessionId);
    }

    /**
     * Check if a session has chat history
     */
    public boolean hasContext(String sessionId) {
        List<ChatMessage> context = chatContexts.get(sessionId);
        return context != null && !context.isEmpty();
    }

    /**
     * Get number of messages in context
     */
    public int getContextSize(String sessionId) {
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
                    ", content='" + (content.length() > 50 ? content.substring(0, 50) + "..." : content) + '\'' +
                    ", timestamp=" + timestamp +
                    '}';
        }
    }
}
