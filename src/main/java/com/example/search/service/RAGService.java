package com.example.search.service;

import com.example.search.dto.RagResponse;
import com.example.search.dto.SearchResultDto;
import com.example.search.dto.VectorDto;
import com.example.search.model.WebPage;
import com.example.search.repository.WebPageRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RAGService {

    private final SearchService searchService;
    private final ChunkerService chunkerService;
    private final ChromaVectorDBService chromaVectorDBService;
    private final ChatService chatService;
    private final ChatContextService chatContextService;
    private final WebPageRepository webPageRepository;
    private final int luceneResultsCount;
    private final int chromaResultsCount;
    private static final int MAX_CONTEXT_LENGTH = 8000;

    public RAGService(
            SearchService searchService,
            ChunkerService chunkerService,
            ChromaVectorDBService chromaVectorDBService,
            ChatService chatService,
            ChatContextService chatContextService,
            WebPageRepository webPageRepository,
            @org.springframework.beans.factory.annotation.Value("${lucene.results.count:10}") int luceneResultsCount,
            @org.springframework.beans.factory.annotation.Value("${chroma.results.count:5}") int chromaResultsCount) {
        this.searchService = searchService;
        this.chunkerService = chunkerService;
        this.chromaVectorDBService = chromaVectorDBService;
        this.chatService = chatService;
        this.chatContextService = chatContextService;
        this.webPageRepository = webPageRepository;
        this.luceneResultsCount = luceneResultsCount;
        this.chromaResultsCount = chromaResultsCount;
    }

    /**
     * Answer a question using lazy RAG:
     * 1. Lucene search for relevant pages
     * 2. Check which pages already have embeddings in ChromaDB
     * 3. Only chunk and embed pages without embeddings (5000 char chunks)
     * 4. Query ChromaDB for similar chunks (with fallback to MongoDB full text)
     * 5. Send to LLM with chat context
     */
    public RagResponse ask(String sessionId, String question) {
        if (question == null || question.trim().isEmpty()) {
            return new RagResponse("Question cannot be empty.", new ArrayList<>());
        }

        try {
            System.out.println("\n========== RAG PIPELINE START ==========");
            System.out.println("Session: " + sessionId);
            System.out.println("Question: " + question);

            // Step 1: Lucene search for relevant pages
            System.out.println("\n--- Step 1: Lucene Search ---");
            List<SearchResultDto> luceneResults = searchService.search(question);
            System.out.println("Lucene returned " + luceneResults.size() + " results");

            if (luceneResults.isEmpty()) {
                System.out.println("No Lucene results found. Returning empty response.");
                return new RagResponse(
                        "I couldn't find any relevant documents to answer your question. Try crawling some websites or re-indexing.",
                        new ArrayList<>()
                );
            }

            // Limit to top N from Lucene
            List<SearchResultDto> topResults = luceneResults.stream()
                    .limit(luceneResultsCount)
                    .collect(Collectors.toList());

            // Step 2 & 3: Check embeddings and generate for missing pages
            System.out.println("\n--- Step 2 & 3: Embedding Check & Generation ---");
            List<ChunkerService.TextChunk> allChunks = new ArrayList<>();
            List<String> urlsNeedingEmbedding = new ArrayList<>();

            for (SearchResultDto result : topResults) {
                boolean hasEmbeddings = false;
                try {
                    hasEmbeddings = chromaVectorDBService.hasEmbeddingsForUrl(result.getUrl());
                } catch (Exception e) {
                    System.err.println("Error checking embeddings for " + result.getUrl() + ": " + e.getMessage());
                }

                if (!hasEmbeddings) {
                    urlsNeedingEmbedding.add(result.getUrl());

                    // Get content from MongoDB and chunk it
                    webPageRepository.findByUrl(result.getUrl()).ifPresent(page -> {
                        String content = page.getContent();
                        if (content != null && !content.trim().isEmpty()) {
                            List<ChunkerService.TextChunk> chunks = chunkerService.chunkWebPage(
                                    page.getUrl(),
                                    page.getTitle() != null ? page.getTitle() : "",
                                    content
                            );
                            allChunks.addAll(chunks);
                        }
                    });
                }
            }

            // Generate embeddings for new chunks
            if (!allChunks.isEmpty()) {
                System.out.println("Generating embeddings for " + urlsNeedingEmbedding.size() +
                        " new pages (" + allChunks.size() + " chunks)");
                try {
                    chromaVectorDBService.addChunks(allChunks);
                } catch (Exception e) {
                    System.err.println("Error storing chunks in ChromaDB: " + e.getMessage());
                }
            }

            // Step 4: Query Chroma for similar chunks
            System.out.println("\n--- Step 4: Vector Similarity Search ---");
            List<VectorDto> similarChunks = new ArrayList<>();
            try {
                similarChunks = chromaVectorDBService.querySimilar(question, chromaResultsCount);
            } catch (Exception e) {
                System.err.println("Vector search failed, falling back to Lucene documents: " + e.getMessage());
            }

            String context;
            List<SearchResultDto> sources;

            if (!similarChunks.isEmpty()) {
                // Build context from similar chunks
                context = similarChunks.stream()
                        .map(v -> {
                            String content = v.getText() != null ? v.getText() : "";
                            if (content.length() > 1500) {
                                content = content.substring(0, 1500) + "... (truncated)";
                            }
                            return "Title: " + v.getTitle() + "\nURL: " + v.getUrl() + "\nContent: " + content;
                        })
                        .collect(Collectors.joining("\n\n---\n\n"));

                sources = similarChunks.stream()
                        .map(v -> new SearchResultDto(v.getUrl(), v.getTitle()))
                        .filter(s -> s.getUrl() != null && !s.getUrl().isEmpty())
                        .distinct()
                        .collect(Collectors.toList());
            } else {
                // Fallback: build context from top Lucene results via MongoDB
                System.out.println("Using Lucene results fallback for context building");
                StringBuilder sb = new StringBuilder();
                List<SearchResultDto> fallbackSources = new ArrayList<>();

                for (SearchResultDto item : topResults) {
                    webPageRepository.findByUrl(item.getUrl()).ifPresent(page -> {
                        String content = page.getContent() != null ? page.getContent() : "";
                        if (content.length() > 1500) {
                            content = content.substring(0, 1500) + "... (truncated)";
                        }
                        sb.append("Title: ").append(page.getTitle()).append("\n")
                          .append("URL: ").append(page.getUrl()).append("\n")
                          .append("Content: ").append(content).append("\n\n---\n\n");
                        fallbackSources.add(new SearchResultDto(page.getUrl(), page.getTitle()));
                    });
                }
                context = sb.toString();
                sources = fallbackSources.stream().distinct().collect(Collectors.toList());
            }

            // Truncate context if needed
            if (context.length() > MAX_CONTEXT_LENGTH) {
                context = context.substring(0, MAX_CONTEXT_LENGTH) + "\n\n... (truncated)";
            }

            // Step 5: Update chat context
            chatContextService.addUserMessage(sessionId, question);

            // Step 6: Get chat history
            String chatHistory = chatContextService.buildContextString(sessionId);

            // Step 7: Send to LLM with context and chat history
            System.out.println("\n--- Step 5-7: LLM Call ---");
            String answer = chatService.chatWithContextAndHistory(question, context, chatHistory);

            // Step 8: Update chat context with assistant response
            chatContextService.addAssistantMessage(sessionId, answer);

            System.out.println("\n========== RAG PIPELINE COMPLETE ==========");
            return new RagResponse(answer, sources);

        } catch (Exception e) {
            System.err.println("Error in RAG ask: " + e.getMessage());
            e.printStackTrace();
            return new RagResponse(
                    "There was an error processing your question: " + e.getMessage(),
                    new ArrayList<>()
            );
        }
    }

    /**
     * Clear chat context for a session
     */
    public void clearChatContext(String sessionId) {
        chatContextService.clearContext(sessionId);
    }

    /**
     * Get chat history for a session
     */
    public java.util.List<java.util.Map<String, Object>> getChatHistory(String sessionId) {
        java.util.List<ChatContextService.ChatMessage> messages = chatContextService.getChatContext(sessionId);
        java.util.List<java.util.Map<String, Object>> history = new java.util.ArrayList<>();
        
        for (ChatContextService.ChatMessage msg : messages) {
            java.util.Map<String, Object> messageMap = new java.util.HashMap<>();
            messageMap.put("role", msg.getRole());
            messageMap.put("content", msg.getContent());
            messageMap.put("timestamp", msg.getTimestamp());
            history.add(messageMap);
        }
        
        return history;
    }

    /**
     * Get Chroma vector DB statistics
     */
    public String getVectorDbStats() {
        return chromaVectorDBService.getCollectionStats();
    }

    /**
     * Clear Chroma vector DB
     */
    public void clearVectorDb() {
        chromaVectorDBService.clearCollection();
    }
}