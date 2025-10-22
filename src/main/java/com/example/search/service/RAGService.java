package com.example.search.service;

import com.example.search.dto.*;
import com.example.search.model.WebPage;
import com.example.search.repository.WebPageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RAGService {

    private final SearchService searchService;
    private final WebClient webClient;
    private final WebPageRepository webPageRepository;

    public RAGService(SearchService searchService, @Value("${api.openrouter.key}") String apiKey, WebPageRepository webPageRepository) {
        this.searchService = searchService;
        this.webPageRepository = webPageRepository;
        this.webClient = WebClient.builder()
                .baseUrl("https://openrouter.ai/api/v1")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    public RagResponse ask(String question) {
        // Step 1: Get up to 10 results from the search service
        List<SearchResultDto> allResults = searchService.search(question);

        if (allResults.isEmpty()) {
            return new RagResponse("I couldn't find any relevant documents to answer your question.", new ArrayList<>());
        }

        // --- FIX 1: Correctly limit the sources used for context ---
        // Keep only the top 2 from that list to build the context for the LLM
        List<SearchResultDto> contextSources = allResults.stream().limit(2).toList(); // Use .limit(2) here

        // Step 3: Build the context string by fetching content from the database for the top 2 sources
        String context = contextSources.stream()
                .map(dto -> {
                    String content = webPageRepository.findByUrl(dto.getUrl())
                            .map(WebPage::getContent)
                            .orElse("[Content not found for this URL]");
                    // Basic length limit per document to prevent one huge doc overwhelming the context
                    if (content.length() > 4000) {
                        content = content.substring(0, 4000) + "... (truncated)";
                    }
                    return "Title: " + dto.getTitle() + "\nContent: " + content;
                })
                .collect(Collectors.joining("\n---\n"));

        // Step 4: Prepare and send the request to the LLM
        OpenRouterRequest request = createOpenRouterRequest(question, context);

        try {
            OpenRouterResponse response = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(OpenRouterResponse.class)
                    .block(); // Using .block() for simplicity

            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                return new RagResponse("Received an empty response from the AI model.", allResults);
            }

            String answer = response.choices().get(0).message().content();

            // Step 5: Return the answer with the FULL list of sources
            return new RagResponse(answer, allResults);

        } catch (Exception e) {
            System.err.println("Error calling LLM API: " + e.getMessage());
            // It's good practice to log the stack trace for debugging
            // e.printStackTrace();
            return new RagResponse("There was an error while communicating with the AI model.", allResults);
        }
    }

    private OpenRouterRequest createOpenRouterRequest(String question, String context) {
        // Max characters for the combined context (retrieved documents)
        final int MAX_CONTEXT_CHARACTERS = 8000; // Roughly 2000 tokens

        // Truncate the *combined* context if it exceeds the overall limit
        if (context.length() > MAX_CONTEXT_CHARACTERS) {
            context = context.substring(0, MAX_CONTEXT_CHARACTERS);
            context += "\n\n--- CONTENT TRUNCATED ---";
        }

        String prompt = String.format(
                "Based *only* on the following documents, please answer the user's question in a concise and helpful natural language form. Do not include special formatting like markdown. If the information needed to answer the question is not in the provided documents, explicitly state that the answer was not found in the documents. Do not use outside knowledge. Mention the source titles relevant to your answer.\n\n" +
                        "User's Question: \"%s\"\n\n" +
                        "Retrieved Documents:\n%s", question, context);

        List<Message> messages = List.of(new Message("user", prompt));

        // --- FIX 2: Correct the model name ---
        // The ':free' suffix is usually not part of the API model ID.
        return new OpenRouterRequest("deepseek/deepseek-chat-v3.1", messages);
    }
}