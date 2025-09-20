package com.example.search.service;

import com.example.search.model.WebPage;
import com.example.search.repository.WebPageRepository;
import com.google.common.util.concurrent.RateLimiter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import java.util.concurrent.*;

@Service
public class CrawlerService {

    private final WebPageRepository webPageRepository;
    private final Set<String> visitedUrls = ConcurrentHashMap.newKeySet();
    private ThreadPoolExecutor executorService;
    private final RateLimiter rateLimiter = RateLimiter.create(5.0);

    public CrawlerService(WebPageRepository webPageRepository) {
        this.webPageRepository = webPageRepository;
    }

    public void startRecursiveCrawl(String startUrl, int maxDepth) {
        executorService = (ThreadPoolExecutor) Executors.newFixedThreadPool(10);
        visitedUrls.clear();
        System.out.println("Starting multithreaded crawl from: " + startUrl);
        submitCrawlTask(startUrl, 0, maxDepth, startUrl);
        monitorAndShutdown();
    }

    // CORRECTED LOGIC
    private void submitCrawlTask(String url, int depth, int maxDepth, String startUrl) {
        // We only check the depth here. The main check will happen inside the thread.
        if (depth > maxDepth) {
            return;
        }

        executorService.submit(() -> {
            try {
                rateLimiter.acquire();

                // --- THIS IS THE SIMPLIFIED AND CORRECTED LOGIC ---
                // 1. Get the response and the final URL after any redirects
                var response = Jsoup.connect(url)
                        .userAgent("MyMiniSearchEngineCrawler/1.0")
                        .timeout(10000)
                        .execute();
                String finalUrl = response.url().toString().split("#")[0];

                // 2. Perform ONE atomic check. If 'add' returns true, it's a new URL.
                if (!visitedUrls.add(finalUrl)) {
                    // This URL has already been queued or processed by another thread.
                    return;
                }

                // 3. Check if it's already in the database from a previous crawl session
                if (webPageRepository.findByUrl(finalUrl).isPresent()) {
                    System.out.println("URL already in database: " + finalUrl);
                    return;
                }

                Document doc = response.parse();
                // --- END OF CORRECTION ---

                String title = doc.title();
                String content = doc.body().text();

                webPageRepository.save(new WebPage(finalUrl, title, content));
                System.out.println("Crawled (Depth: " + depth + "): " + title);

                Elements linkElements = doc.select("a[href]");
                for (var linkElement : linkElements) {
                    String absUrl = linkElement.attr("abs:href").split("#")[0];
                    if (!absUrl.isEmpty() && isSameDomain(startUrl, absUrl)) {
                        submitCrawlTask(absUrl, depth + 1, maxDepth, startUrl);
                    }
                }
            } catch (IOException | IllegalArgumentException e) {
                // It's okay for some URLs to fail, just log it.
                // System.err.println("Error crawling " + url + ": " + e.getMessage());
            }
        });
    }

    private void monitorAndShutdown() {
        while (true) {
            if (executorService.getQueue().isEmpty() && executorService.getActiveCount() == 0) {
                executorService.shutdown();
                try {
                    if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                        executorService.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executorService.shutdownNow();
                }
                System.out.println("Finished multithreaded crawl. Visited " + visitedUrls.size() + " unique URLs.");
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private boolean isSameDomain(String startUrl, String newUrl) {
        try {
            URI startUri = new URI(startUrl);
            URI newUri = new URI(newUrl);
            String startDomain = startUri.getHost();
            String newDomain = newUri.getHost();
            return newDomain != null && newDomain.endsWith(startDomain);
        } catch (URISyntaxException e) {
            return false;
        }
    }
}