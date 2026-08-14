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

import java.util.ArrayList;
import java.util.List;

@Service
public class EmbeddingsService {

    private final WebClient webClient;
    private final String embeddingModel;
    private final String apiKey;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EmbeddingsService(
            @Value("${openrouter.api.url:https://openrouter.ai/api/v1}") String openRouterUrl,
            @Value("${openrouter.embedding.model:nvidia/llama-nemotron-embed-vl-1b-v2:free}") String embeddingModel,
            @Value("${openrouter.api.key}") String apiKey) {
        this.embeddingModel = embeddingModel;
        this.apiKey = apiKey;
        this.webClient = WebClient.builder()
                .baseUrl(openRouterUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader("HTTP-Referer", "http://localhost:8080")
                .defaultHeader("X-Title", "Tux Search Engine")
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        
        System.out.println("EmbeddingsService initialized with model: " + embeddingModel);
    }

    /**
     * Generate embeddings for the given text using OpenRouter.
     * Uses ObjectMapper for safe JSON construction (handles all special characters).
     */
    public float[] generateEmbedding(String text) {
        if (text == null || text.trim().isEmpty()) {
            System.err.println("Empty text passed to generateEmbedding");
            return new float[0];
        }

        try {
            // Truncate very long text to avoid API limits (most embedding models have ~8192 token limit)
            String inputText = text.length() > 12000 ? text.substring(0, 12000) : text;

            // Build request using ObjectMapper for safe JSON (handles newlines, quotes, unicode, etc.)
            ObjectNode requestNode = objectMapper.createObjectNode();
            requestNode.put("model", embeddingModel);
            requestNode.put("input", inputText);

            String request = objectMapper.writeValueAsString(requestNode);

            System.out.println("Generating embedding for text of length " + inputText.length() + " using model " + embeddingModel);

            String response = webClient.post()
                    .uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            // Parse the response to extract embeddings
            float[] embedding = parseEmbeddingResponse(response);
            
            if (embedding.length > 0) {
                System.out.println("Embedding generated successfully, dimension=" + embedding.length);
            } else {
                System.err.println("Embedding response parsed but empty. Raw response: " + 
                    (response != null && response.length() > 500 ? response.substring(0, 500) + "..." : response));
            }
            
            return embedding;

        } catch (Exception e) {
            System.err.println("Error generating embedding: " + e.getMessage());
            e.printStackTrace();
            return new float[0];
        }
    }

    /**
     * Generate embeddings for multiple texts in batch.
     * Processes one at a time to avoid rate limiting.
     */
    public List<float[]> generateEmbeddingsBatch(List<String> texts) {
        List<float[]> embeddings = new ArrayList<>();
        int success = 0;
        int failed = 0;
        
        for (int i = 0; i < texts.size(); i++) {
            System.out.println("Generating embedding " + (i + 1) + "/" + texts.size());
            float[] embedding = generateEmbedding(texts.get(i));
            embeddings.add(embedding);
            
            if (embedding.length > 0) {
                success++;
            } else {
                failed++;
            }

            // Small delay between API calls to avoid rate limiting
            if (i < texts.size() - 1) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        
        System.out.println("Batch embedding complete: " + success + " succeeded, " + failed + " failed out of " + texts.size());
        return embeddings;
    }

    /**
     * Parse the OpenRouter embedding API response.
     * Response format: {"object":"list","data":[{"object":"embedding","embedding":[...]}]}
     */
    private float[] parseEmbeddingResponse(String responseJson) throws Exception {
        if (responseJson == null || responseJson.isEmpty()) {
            System.err.println("Empty embedding response from API");
            return new float[0];
        }

        JsonNode root = objectMapper.readTree(responseJson);
        
        // Check for error response
        if (root.has("error")) {
            JsonNode errorNode = root.get("error");
            String errorMsg = errorNode.isObject() && errorNode.has("message") 
                ? errorNode.get("message").asText() 
                : errorNode.asText();
            System.err.println("Embedding API error: " + errorMsg);
            return new float[0];
        }

        JsonNode dataNode = root.get("data");
        
        if (dataNode != null && dataNode.isArray() && dataNode.size() > 0) {
            JsonNode embeddingNode = dataNode.get(0).get("embedding");
            if (embeddingNode != null && embeddingNode.isArray()) {
                float[] embedding = new float[embeddingNode.size()];
                for (int i = 0; i < embeddingNode.size(); i++) {
                    embedding[i] = (float) embeddingNode.get(i).asDouble();
                }
                return embedding;
            }
        }
        
        System.err.println("Failed to parse embedding response structure. Keys: " + root.fieldNames());
        return new float[0];
    }
}
