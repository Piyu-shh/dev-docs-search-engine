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
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class CrawlerService {

    private final WebPageRepository webPageRepository;
    private final Set<String> visitedUrls = ConcurrentHashMap.newKeySet();
    private final RateLimiter rateLimiter = RateLimiter.create(5.0);
    private final AtomicInteger inFlightTasks = new AtomicInteger(0);

    public CrawlerService(WebPageRepository webPageRepository) {
        this.webPageRepository = webPageRepository;
    }

    public synchronized void startRecursiveCrawl(String startUrl, int maxDepth) {
        if (startUrl == null || startUrl.trim().isEmpty()) {
            return;
        }

        String normalizedStartUrl = normalizeUrl(startUrl);
        visitedUrls.clear();
        inFlightTasks.set(0);

        ThreadPoolExecutor executorService = (ThreadPoolExecutor) Executors.newFixedThreadPool(10);
        System.out.println("Starting multithreaded crawl from: " + normalizedStartUrl);

        submitCrawlTask(executorService, normalizedStartUrl, 0, maxDepth, normalizedStartUrl);
        monitorAndShutdown(executorService);
    }

    private void submitCrawlTask(ThreadPoolExecutor executorService, String url, int depth, int maxDepth, String startUrl) {
        if (depth > maxDepth || executorService.isShutdown()) {
            return;
        }

        String normalizedUrl = normalizeUrl(url);
        if (normalizedUrl.isEmpty()) {
            return;
        }

        // Check visited before submitting to avoid queue bloat and redundant requests
        if (!visitedUrls.add(normalizedUrl)) {
            return;
        }

        inFlightTasks.incrementAndGet();

        executorService.submit(() -> {
            try {
                rateLimiter.acquire();

                var response = Jsoup.connect(normalizedUrl)
                        .userAgent("MyMiniSearchEngineCrawler/1.0")
                        .timeout(10000)
                        .followRedirects(true)
                        .execute();

                String finalUrl = normalizeUrl(response.url().toString());
                if (!finalUrl.isEmpty()) {
                    visitedUrls.add(finalUrl);
                }

                Document doc = response.parse();
                String title = doc.title() != null ? doc.title() : "";
                String content = doc.body() != null ? doc.body().text() : "";

                // Only save if not already present in database
                if (webPageRepository.findByUrl(finalUrl).isEmpty()) {
                    webPageRepository.save(new WebPage(finalUrl, title, content));
                    System.out.println("Crawled (Depth: " + depth + "): " + title + " [" + finalUrl + "]");
                } else {
                    System.out.println("URL already in database: " + finalUrl);
                }

                if (depth < maxDepth) {
                    Elements linkElements = doc.select("a[href]");
                    for (var linkElement : linkElements) {
                        String absUrl = normalizeUrl(linkElement.attr("abs:href"));
                        if (!absUrl.isEmpty() && isSameDomain(startUrl, absUrl)) {
                            submitCrawlTask(executorService, absUrl, depth + 1, maxDepth, startUrl);
                        }
                    }
                }
            } catch (IOException | IllegalArgumentException e) {
                // Log and ignore individual crawl failures
            } finally {
                inFlightTasks.decrementAndGet();
            }
        });
    }

    private void monitorAndShutdown(ThreadPoolExecutor executorService) {
        try {
            while (inFlightTasks.get() > 0 || !executorService.getQueue().isEmpty() || executorService.getActiveCount() > 0) {
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
            System.out.println("Finished multithreaded crawl. Visited " + visitedUrls.size() + " unique URLs.");
        }
    }

    private String normalizeUrl(String url) {
        if (url == null) return "";
        String trimmed = url.trim();
        int hashIdx = trimmed.indexOf('#');
        return hashIdx != -1 ? trimmed.substring(0, hashIdx) : trimmed;
    }

    private boolean isSameDomain(String startUrl, String newUrl) {
        try {
            URI startUri = new URI(startUrl);
            URI newUri = new URI(newUrl);
            String startDomain = startUri.getHost();
            String newDomain = newUri.getHost();
            if (startDomain == null || newDomain == null) {
                return false;
            }
            startDomain = startDomain.toLowerCase();
            newDomain = newDomain.toLowerCase();
            return newDomain.equals(startDomain) || newDomain.endsWith("." + startDomain);
        } catch (URISyntaxException e) {
            return false;
        }
    }
}