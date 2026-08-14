package com.example.search.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class ChatService {

    private final WebClient webClient;
    private final String model;
    private final String apiKey;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatService(
            @Value("${openrouter.api.url:https://openrouter.ai/api/v1}") String openRouterUrl,
            @Value("${openrouter.chat.model:qwen/qwen3-coder:free}") String model,
            @Value("${openrouter.api.key}") String apiKey) {
        this.model = model;
        this.apiKey = apiKey;
        this.webClient = WebClient.builder()
                .baseUrl(openRouterUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader("HTTP-Referer", "http://localhost:8080")
                .defaultHeader("X-Title", "Tux Search Engine")
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        
        System.out.println("ChatService initialized with model: " + model);
    }

    /**
     * Send a message to the LLM and get a response
     */
    public String chat(String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return "User message cannot be empty.";
        }

        try {
            String jsonRequest = buildChatRequest(userMessage);
            
            System.out.println("Sending chat request to LLM, message length=" + userMessage.length());

            String response = webClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(jsonRequest)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            String parsed = parseChatResponse(response);
            System.out.println("LLM response received, length=" + parsed.length());
            return parsed;

        } catch (Exception e) {
            System.err.println("Error calling LLM: " + e.getMessage());
            e.printStackTrace();
            return "Error communicating with the AI model: " + e.getMessage();
        }
    }

    /**
     * Generate RAG context-aware response
     */
    public String chatWithContext(String userMessage, String context) {
        String prompt = buildRagPrompt(userMessage, context);
        return chat(prompt);
    }

    /**
     * Generate RAG response with chat history context
     */
    public String chatWithContextAndHistory(String userMessage, String context, String chatHistory) {
        String prompt = buildRagPromptWithHistory(userMessage, context, chatHistory);
        return chat(prompt);
    }

    /**
     * Build RAG prompt with context only
     */
    private String buildRagPrompt(String question, String context) {
        return String.format(
                "Based *only* on the following documents, please answer the user's question in a concise and helpful natural language form. " +
                "Do not include special formatting like markdown. If the information needed to answer the question is not in the provided documents, " +
                "explicitly state that the answer was not found in the documents. Do not use outside knowledge. Mention the source titles relevant to your answer.\n\n" +
                "User's Question: \"%s\"\n\n" +
                "Retrieved Documents:\n%s",
                question, context
        );
    }

    /**
     * Build RAG prompt with context AND chat history
     */
    private String buildRagPromptWithHistory(String question, String context, String chatHistory) {
        return String.format(
                "You are having a conversation with a user. Use the chat history for context, and the provided documents to answer the question.\n\n" +
                "%s\n\n" +
                "Based *only* on the following documents, please answer the user's current question. " +
                "If the information is not in the documents, say so. Do not use outside knowledge.\n\n" +
                "Current Question: \"%s\"\n\n" +
                "Retrieved Documents:\n%s\n\n" +
                "Please provide a concise, helpful answer mentioning relevant source titles.",
                chatHistory, question, context
        );
    }

    /**
     * Build chat request for OpenRouter API using ObjectMapper for safe JSON construction.
     * This properly handles all special characters in user messages (quotes, newlines, unicode, etc.)
     */
    private String buildChatRequest(String message) {
        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", model);
            
            ArrayNode messages = objectMapper.createArrayNode();
            ObjectNode userMsg = objectMapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", message);
            messages.add(userMsg);
            
            request.set("messages", messages);
            request.put("temperature", 0.7);
            request.put("max_tokens", 2000);
            
            return objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            System.err.println("Error building chat request: " + e.getMessage());
            // Fallback - should never happen with ObjectMapper
            return "{}";
        }
    }

    /**
     * Parse OpenRouter chat completions API response.
     * Response format: {"choices": [{"message": {"role": "assistant", "content": "response text"}}]}
     */
    private String parseChatResponse(String responseJson) throws Exception {
        if (responseJson == null || responseJson.isEmpty()) {
            return "Empty response from model";
        }

        JsonNode root = objectMapper.readTree(responseJson);
        
        // Check for error response
        if (root.has("error")) {
            JsonNode errorNode = root.get("error");
            String errorMsg = errorNode.isObject() && errorNode.has("message") 
                ? errorNode.get("message").asText() 
                : errorNode.asText();
            System.err.println("LLM API error: " + errorMsg);
            return "AI model returned an error: " + errorMsg;
        }
        
        JsonNode choicesNode = root.get("choices");
        
        if (choicesNode != null && choicesNode.isArray() && choicesNode.size() > 0) {
            JsonNode messageNode = choicesNode.get(0).get("message");
            if (messageNode != null) {
                JsonNode contentNode = messageNode.get("content");
                if (contentNode != null) {
                    return contentNode.asText();
                }
            }
        }

        System.err.println("Unable to parse LLM response: " + responseJson);
        return "Unable to parse response";
    }
}
