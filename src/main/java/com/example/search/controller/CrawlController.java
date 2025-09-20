package com.example.search.controller;

import com.example.search.dto.RagResponse;
import com.example.search.dto.SearchResultDto;
import com.example.search.service.CrawlerService;
import com.example.search.service.IndexingService;
import com.example.search.service.RAGService;
import com.example.search.service.SearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api")
public class CrawlController {

    private final CrawlerService crawlerService;

    private final IndexingService indexingService;

    private final SearchService searchService;

    private final RAGService ragService;

    // Update the constructor to accept all three services
    public CrawlController(CrawlerService crawlerService,
                           IndexingService indexingService,
                           SearchService searchService, RAGService ragService) { // <-- Add SearchService
        this.crawlerService = crawlerService;
        this.indexingService = indexingService;
        this.searchService = searchService; // <-- Add this
        this.ragService = ragService;
    }

    // Then, add the new search endpoint method
    @GetMapping("/search")
    public ResponseEntity<List<SearchResultDto>> search(@RequestParam String q) {
        if (q == null || q.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(new ArrayList<>());
        }
        List<SearchResultDto> results = searchService.search(q);
        return ResponseEntity.ok(results);
    }

    // Then, add the new endpoint method
    @GetMapping("/index")
    public ResponseEntity<String> startIndexing() {
        indexingService.reIndexAll();
        return ResponseEntity.ok("Re-indexing of all crawled pages initiated.");
    }


    @GetMapping("/crawl")
    public ResponseEntity<String> startCrawling(
            @RequestParam String startUrl,
            @RequestParam(defaultValue = "2") int depth) { // Depth of 2 = start page + pages linked from it

        if (startUrl == null || startUrl.isEmpty()) {
            return ResponseEntity.badRequest().body("startUrl parameter is required.");
        }

        // Use a separate thread to not block the API response
        new Thread(() -> crawlerService.startRecursiveCrawl(startUrl, depth)).start();

        return ResponseEntity.ok("Recursive crawl initiated for: " + startUrl + " up to depth " + depth);
    }

    @GetMapping("/ask")
    public ResponseEntity<RagResponse> ask(@RequestParam String q) {
        if (q == null || q.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(new RagResponse("Question cannot be empty.", new ArrayList<>()));
        }
        return ResponseEntity.ok(ragService.ask(q));
    }
}
