package com.example.search.service;

import com.example.search.dto.VectorDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import jakarta.annotation.PostConstruct;
import java.util.*;

@Service
public class ChromaVectorDBService {

    private final WebClient webClient;
    private final EmbeddingsService embeddingsService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String collectionName;
    private String collectionId; // UUID returned by Chroma

    // ChromaDB v2 API requires tenant and database in URL
    private static final String DEFAULT_TENANT = "default_tenant";
    private static final String DEFAULT_DATABASE = "default_database";
    private static final String V2_BASE = "/api/v2/tenants/" + DEFAULT_TENANT + "/databases/" + DEFAULT_DATABASE;

    public ChromaVectorDBService(
            @Value("${chroma.url:http://localhost:8000}") String chromaUrl,
            EmbeddingsService embeddingsService) {
        this.embeddingsService = embeddingsService;
        this.collectionName = "web_pages_chunks";
        this.webClient = WebClient.builder()
                .baseUrl(chromaUrl)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    @PostConstruct
    public void init() {
        try {
            initializeCollection();
        } catch (Exception e) {
            System.err.println("WARNING: Could not initialize Chroma collection at startup: " + e.getMessage());
            System.err.println("Make sure ChromaDB is running. Will retry on first use.");
        }
    }

    /**
     * Initialize or get existing collection using ChromaDB v2 API, storing its UUID.
     */
    private void initializeCollection() {
        try {
            // List existing collections and look for ours
            String listResponse = webClient.get()
                    .uri(V2_BASE + "/collections")
                    .retrieve()
                    .bodyToMono(String.class)
                    .onErrorResume(e -> {
                        System.err.println("Failed to list collections: " + e.getMessage());
                        return Mono.just("[]");
                    })
                    .block();

            if (listResponse != null) {
                JsonNode collections = objectMapper.readTree(listResponse);
                if (collections.isArray()) {
                    for (JsonNode col : collections) {
                        if (col.has("name") && collectionName.equals(col.get("name").asText())) {
                            this.collectionId = col.get("id").asText();
                            System.out.println("Chroma collection found: " + collectionName + " (id=" + collectionId + ")");
                            return;
                        }
                    }
                }
            }

            // Collection doesn't exist - create it
            ObjectNode createReq = objectMapper.createObjectNode();
            createReq.put("name", collectionName);
            ObjectNode metadata = objectMapper.createObjectNode();
            metadata.put("hnsw:space", "cosine");
            createReq.set("metadata", metadata);

            String createResponse = webClient.post()
                    .uri(V2_BASE + "/collections")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(objectMapper.writeValueAsString(createReq))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (createResponse != null) {
                JsonNode node = objectMapper.readTree(createResponse);
                if (node.has("id")) {
                    this.collectionId = node.get("id").asText();
                    System.out.println("Chroma collection CREATED: " + collectionName + " (id=" + collectionId + ")");
                } else {
                    System.err.println("Created collection but no ID returned: " + createResponse);
                }
            }

        } catch (Exception e) {
            System.err.println("Error initializing Chroma collection: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Ensure collection is initialized before any operation
     */
    private void ensureCollection() {
        if (this.collectionId == null) {
            initializeCollection();
        }
        if (this.collectionId == null) {
            throw new RuntimeException("ChromaDB collection not available. Is ChromaDB running?");
        }
    }

    /**
     * Add text chunks to Chroma with embeddings using v2 API
     */
    public void addChunks(List<ChunkerService.TextChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        try {
            ensureCollection();

            // Prepare batch data
            List<String> ids = new ArrayList<>();
            List<String> documents = new ArrayList<>();
            List<Map<String, String>> metadatas = new ArrayList<>();

            for (ChunkerService.TextChunk chunk : chunks) {
                ids.add(chunk.getChunkId());
                documents.add(chunk.getText());

                Map<String, String> metadata = new HashMap<>();
                metadata.put("sourceUrl", chunk.getSourceUrl());
                metadata.put("title", chunk.getTitle());
                metadata.put("chunkIndex", String.valueOf(chunk.getChunkIndex()));
                metadata.put("totalChunks", String.valueOf(chunk.getTotalChunks()));
                metadatas.add(metadata);
            }

            // Generate embeddings for all chunks
            List<float[]> embeddings = embeddingsService.generateEmbeddingsBatch(documents);

            // Filter out chunks with empty embeddings
            List<String> validIds = new ArrayList<>();
            List<String> validDocs = new ArrayList<>();
            List<Map<String, String>> validMetas = new ArrayList<>();
            List<float[]> validEmbeddings = new ArrayList<>();

            for (int i = 0; i < ids.size(); i++) {
                if (i < embeddings.size() && embeddings.get(i) != null && embeddings.get(i).length > 0) {
                    validIds.add(ids.get(i));
                    validDocs.add(documents.get(i));
                    validMetas.add(metadatas.get(i));
                    validEmbeddings.add(embeddings.get(i));
                } else {
                    System.err.println("WARNING: Skipping chunk with empty embedding: " + ids.get(i));
                }
            }

            if (validIds.isEmpty()) {
                System.err.println("ERROR: No valid embeddings after filtering. Skipping add.");
                return;
            }

            // Build request using ObjectMapper (safe JSON)
            ObjectNode request = objectMapper.createObjectNode();

            // IDs
            ArrayNode idsArray = objectMapper.createArrayNode();
            validIds.forEach(idsArray::add);
            request.set("ids", idsArray);

            // Embeddings
            ArrayNode embeddingsArray = objectMapper.createArrayNode();
            for (float[] emb : validEmbeddings) {
                ArrayNode embArray = objectMapper.createArrayNode();
                for (float v : emb) {
                    embArray.add(v);
                }
                embeddingsArray.add(embArray);
            }
            request.set("embeddings", embeddingsArray);

            // Metadatas
            ArrayNode metaArray = objectMapper.createArrayNode();
            for (Map<String, String> meta : validMetas) {
                ObjectNode metaNode = objectMapper.createObjectNode();
                meta.forEach(metaNode::put);
                metaArray.add(metaNode);
            }
            request.set("metadatas", metaArray);

            // Documents
            ArrayNode docsArray = objectMapper.createArrayNode();
            validDocs.forEach(docsArray::add);
            request.set("documents", docsArray);

            String jsonRequest = objectMapper.writeValueAsString(request);
            System.out.println("Adding " + validIds.size() + " chunks to Chroma collection " + collectionId);

            // Send to Chroma v2 API - upsert
            String response = webClient.post()
                    .uri(V2_BASE + "/collections/" + collectionId + "/upsert")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(jsonRequest)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            System.out.println("Successfully added " + validIds.size() + " chunks to Chroma. Response: " + response);

        } catch (Exception e) {
            System.err.println("Error adding chunks to Chroma: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Query Chroma for similar chunks using v2 API
     */
    public List<VectorDto> querySimilar(String queryText, int nResults) {
        try {
            ensureCollection();

            // Generate embedding for query
            float[] queryEmbedding = embeddingsService.generateEmbedding(queryText);
            if (queryEmbedding == null || queryEmbedding.length == 0) {
                System.err.println("ERROR: Could not generate embedding for query: " + queryText);
                return new ArrayList<>();
            }

            System.out.println("Query embedding generated, dimension=" + queryEmbedding.length);

            // Build query request using ObjectMapper
            ObjectNode request = objectMapper.createObjectNode();

            // query_embeddings: [[...]] - array of arrays (one per query)
            ArrayNode queryEmbeddings = objectMapper.createArrayNode();
            ArrayNode singleQuery = objectMapper.createArrayNode();
            for (float v : queryEmbedding) {
                singleQuery.add(v);
            }
            queryEmbeddings.add(singleQuery);
            request.set("query_embeddings", queryEmbeddings);

            // n_results
            request.put("n_results", nResults);

            // include: request documents, metadatas, and distances back
            ArrayNode include = objectMapper.createArrayNode();
            include.add("documents");
            include.add("metadatas");
            include.add("distances");
            request.set("include", include);

            String jsonRequest = objectMapper.writeValueAsString(request);
            System.out.println("Querying Chroma collection " + collectionId + " with n_results=" + nResults);

            // Send query to v2 API
            String response = webClient.post()
                    .uri(V2_BASE + "/collections/" + collectionId + "/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(jsonRequest)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            System.out.println("Chroma query response received, length=" + (response != null ? response.length() : 0));
            if (response != null && response.length() < 2000) {
                System.out.println("Chroma response: " + response);
            }

            return parseQueryResponse(response);

        } catch (Exception e) {
            System.err.println("Error querying Chroma: " + e.getMessage());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    /**
     * Parse query response from Chroma v2 API.
     * 
     * IMPORTANT: Chroma query response uses NESTED ARRAYS because you can send
     * multiple query vectors. The format is:
     * {
     *   "ids": [["id1", "id2"]],           // ids[0] = results for first query
     *   "documents": [["doc1", "doc2"]],    // documents[0] = results for first query
     *   "metadatas": [[{...}, {...}]],      // metadatas[0] = results for first query
     *   "distances": [[0.1, 0.2]]           // distances[0] = results for first query
     * }
     */
    private List<VectorDto> parseQueryResponse(String response) {
        List<VectorDto> results = new ArrayList<>();

        if (response == null || response.isEmpty()) {
            System.err.println("Empty response from Chroma query");
            return results;
        }

        try {
            JsonNode root = objectMapper.readTree(response);

            // Check for error in response
            if (root.has("error")) {
                System.err.println("Chroma query error: " + root.get("error").asText());
                return results;
            }

            JsonNode idsNode = root.get("ids");
            JsonNode documentsNode = root.get("documents");
            JsonNode metadatasNode = root.get("metadatas");
            JsonNode distancesNode = root.get("distances");

            if (idsNode == null || !idsNode.isArray() || idsNode.size() == 0) {
                System.err.println("No ids in Chroma response. Full response: " + response);
                return results;
            }

            // *** KEY FIX: Get the FIRST element (nested array) ***
            // idsNode = [["id1", "id2"]], so idsNode.get(0) = ["id1", "id2"]
            JsonNode firstQueryIds = idsNode.get(0);
            JsonNode firstQueryDocs = (documentsNode != null && documentsNode.isArray() && documentsNode.size() > 0)
                    ? documentsNode.get(0) : null;
            JsonNode firstQueryMetas = (metadatasNode != null && metadatasNode.isArray() && metadatasNode.size() > 0)
                    ? metadatasNode.get(0) : null;
            JsonNode firstQueryDists = (distancesNode != null && distancesNode.isArray() && distancesNode.size() > 0)
                    ? distancesNode.get(0) : null;

            if (firstQueryIds == null || !firstQueryIds.isArray()) {
                System.err.println("First query ids is not an array. Response structure unexpected.");
                return results;
            }

            System.out.println("Chroma returned " + firstQueryIds.size() + " results");

            for (int i = 0; i < firstQueryIds.size(); i++) {
                String id = firstQueryIds.get(i).asText();

                String text = "";
                if (firstQueryDocs != null && firstQueryDocs.isArray() && i < firstQueryDocs.size()) {
                    text = firstQueryDocs.get(i).asText();
                }

                String url = "";
                String title = "";
                if (firstQueryMetas != null && firstQueryMetas.isArray() && i < firstQueryMetas.size()) {
                    JsonNode metadata = firstQueryMetas.get(i);
                    if (metadata != null && !metadata.isNull()) {
                        url = metadata.has("sourceUrl") ? metadata.get("sourceUrl").asText() : "";
                        title = metadata.has("title") ? metadata.get("title").asText() : "";
                    }
                }

                double distance = -1;
                if (firstQueryDists != null && firstQueryDists.isArray() && i < firstQueryDists.size()) {
                    distance = firstQueryDists.get(i).asDouble();
                }

                System.out.println("  Result " + i + ": id=" + id + ", title=" + title + ", distance=" + distance
                        + ", textLen=" + text.length());

                VectorDto vector = new VectorDto(id, null, text, url, title, System.currentTimeMillis());
                results.add(vector);
            }

        } catch (Exception e) {
            System.err.println("Error parsing Chroma response: " + e.getMessage());
            e.printStackTrace();
        }
        return results;
    }

    /**
     * Clear all chunks from collection using v2 API
     */
    public void clearCollection() {
        try {
            if (collectionId != null) {
                // Delete collection by name
                webClient.delete()
                        .uri(V2_BASE + "/collections/" + collectionName)
                        .retrieve()
                        .bodyToMono(String.class)
                        .onErrorResume(e -> {
                            System.err.println("Error deleting collection: " + e.getMessage());
                            return Mono.just("{}");
                        })
                        .block();
            }

            this.collectionId = null;
            // Recreate collection
            initializeCollection();
            System.out.println("Chroma collection cleared and recreated");
        } catch (Exception e) {
            System.err.println("Error clearing Chroma collection: " + e.getMessage());
        }
    }

    /**
     * Check if embeddings already exist for a given URL using v2 API
     */
    public boolean hasEmbeddingsForUrl(String url) {
        try {
            ensureCollection();

            // Build proper "get" request with "where" clause for v2 API
            ObjectNode request = objectMapper.createObjectNode();
            ObjectNode where = objectMapper.createObjectNode();
            where.put("sourceUrl", url);
            request.set("where", where);
            request.put("limit", 1);

            // Include only ids to minimize response size
            ArrayNode include = objectMapper.createArrayNode();
            request.set("include", include);

            String jsonRequest = objectMapper.writeValueAsString(request);

            String response = webClient.post()
                    .uri(V2_BASE + "/collections/" + collectionId + "/get")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(jsonRequest)
                    .retrieve()
                    .bodyToMono(String.class)
                    .onErrorResume(e -> {
                        System.err.println("Error checking embeddings for URL: " + e.getMessage());
                        return Mono.just("{}");
                    })
                    .block();

            if (response != null) {
                JsonNode node = objectMapper.readTree(response);
                // The /get endpoint returns flat arrays (not nested like /query)
                if (node.has("ids") && node.get("ids").isArray() && node.get("ids").size() > 0) {
                    return true;
                }
            }

            return false;
        } catch (Exception e) {
            System.err.println("Error in hasEmbeddingsForUrl: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get collection statistics using v2 API
     */
    public String getCollectionStats() {
        try {
            ensureCollection();

            String response = webClient.get()
                    .uri(V2_BASE + "/collections/" + collectionId + "/count")
                    .retrieve()
                    .bodyToMono(String.class)
                    .onErrorResume(e -> {
                        System.err.println("Error getting count: " + e.getMessage());
                        return Mono.just("0");
                    })
                    .block();

            int count = 0;
            if (response != null) {
                try {
                    count = Integer.parseInt(response.trim());
                } catch (NumberFormatException e) {
                    JsonNode node = objectMapper.readTree(response);
                    count = node.has("count") ? node.get("count").asInt() : 0;
                }
            }
            return String.format("Chroma Collection: %s, Chunks: %d", collectionName, count);
        } catch (Exception e) {
            return "Error getting Chroma stats: " + e.getMessage();
        }
    }
}
