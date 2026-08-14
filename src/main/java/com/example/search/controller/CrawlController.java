package com.example.search.controller;

import com.example.search.dto.RagResponse;
import com.example.search.dto.SearchResultDto;
import com.example.search.service.CrawlerService;
import com.example.search.service.IndexingService;
import com.example.search.service.RAGService;
import com.example.search.service.SearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class CrawlController {

    private final CrawlerService crawlerService;
    private final IndexingService indexingService;
    private final SearchService searchService;
    private final RAGService ragService;

    public CrawlController(CrawlerService crawlerService,
                           IndexingService indexingService,
                           SearchService searchService,
                           RAGService ragService) {
        this.crawlerService = crawlerService;
        this.indexingService = indexingService;
        this.searchService = searchService;
        this.ragService = ragService;
    }

    @GetMapping("/crawl")
    public ResponseEntity<String> startCrawling(
            @RequestParam String startUrl,
            @RequestParam(defaultValue = "2") int depth) {

        if (startUrl == null || startUrl.isEmpty()) {
            return ResponseEntity.badRequest().body("startUrl parameter is required.");
        }

        new Thread(() -> crawlerService.startRecursiveCrawl(startUrl, depth)).start();
        return ResponseEntity.ok("Recursive crawl initiated for: " + startUrl + " up to depth " + depth);
    }

    @GetMapping("/index")
    public ResponseEntity<String> startIndexing() {
        new Thread(indexingService::reIndexAll).start();
        return ResponseEntity.ok("Re-indexing of all crawled pages initiated. This includes Lucene full-text index.");
    }

    @GetMapping("/search")
    public ResponseEntity<List<SearchResultDto>> search(@RequestParam String q) {
        if (q == null || q.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(new ArrayList<>());
        }
        List<SearchResultDto> results = searchService.search(q);
        return ResponseEntity.ok(results);
    }

    /**
     * Ask a question with session-based chat context
     * Session ID is optional - if not provided, a new one is created
     */
    @GetMapping("/ask")
    public ResponseEntity<RagResponse> ask(
            @RequestParam String q,
            @RequestParam(value = "sessionId", required = false) String sessionId) {

        if (q == null || q.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(new RagResponse("Question cannot be empty.", new ArrayList<>()));
        }

        // Generate session ID if not provided
        if (sessionId == null || sessionId.trim().isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }

        RagResponse response = ragService.ask(sessionId, q);
        return ResponseEntity.ok(response);
    }

    /**
     * Clear chat context for a session
     */
    @GetMapping("/chat/clear")
    public ResponseEntity<String> clearChatContext(
            @RequestParam String sessionId) {

        if (sessionId == null || sessionId.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("sessionId parameter is required.");
        }

        ragService.clearChatContext(sessionId);
        return ResponseEntity.ok("Chat context cleared for session: " + sessionId);
    }

    /**
     * Get chat history for a session
     */
    @GetMapping("/chat/history")
    public ResponseEntity<java.util.List<java.util.Map<String, Object>>> getChatHistory(
            @RequestParam String sessionId) {

        if (sessionId == null || sessionId.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(new ArrayList<>());
        }

        java.util.List<java.util.Map<String, Object>> history = ragService.getChatHistory(sessionId);
        return ResponseEntity.ok(history);
    }

    /**
     * Get Chroma vector database statistics
     */
    @GetMapping("/vector-db/stats")
    public ResponseEntity<String> getVectorDbStats() {
        return ResponseEntity.ok(ragService.getVectorDbStats());
    }

    /**
     * Clear Chroma vector database
     */
    @GetMapping("/vector-db/clear")
    public ResponseEntity<String> clearVectorDb() {
        new Thread(ragService::clearVectorDb).start();
        return ResponseEntity.ok("Chroma vector database cleared. Re-indexing recommended.");
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Search service is running. " + ragService.getVectorDbStats());
    }
}
