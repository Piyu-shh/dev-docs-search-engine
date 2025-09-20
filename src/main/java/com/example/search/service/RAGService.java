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

        // Step 2: Keep only the top 2 to build the context for the LLM
        List<SearchResultDto> contextSources = allResults.stream().toList();

        // Step 3: Build the context string by fetching content from the database for the top 2 sources
        String context = contextSources.stream()
                .map(dto -> {
                    String content = webPageRepository.findByUrl(dto.getUrl())
                            .map(WebPage::getContent)
                            .orElse("[Content not found for this URL]");
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
                    .block();

            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                // CORRECTED: Return the full list of results for consistency
                return new RagResponse("Received an empty response from the AI model.", allResults);
            }

            String answer = response.choices().get(0).message().content();

            // Step 5: Return the answer with the FULL list of sources
            return new RagResponse(answer, allResults);

        } catch (Exception e) {
            System.err.println("Error calling LLM API: " + e.getMessage());
            return new RagResponse("There was an error while communicating with the AI model.", allResults);
        }
    }

    private OpenRouterRequest createOpenRouterRequest(String question,String context) {

        int MAX_CONTEXT_CHARACTERS = 8000;

        if (context.length() > MAX_CONTEXT_CHARACTERS) {
            context = context.substring(0, MAX_CONTEXT_CHARACTERS);
            // It's helpful to let the model know the content was shortened.
            context += "\n\n--- CONTENT TRUNCATED ---";
        }

        String prompt = "Based on the following documents, please answer the user's question in a helpful form in a natural language. Do not include special formatted text. If information is unavailable, then just say not found in the docs, don't give your own answer. Mention the source documents.\n\n" +
                "User's Question:\n" + question +
                "Retrieved Documents:\n" + context;

        List<Message> messages = List.of(new Message("user", prompt));

        // CORRECTED: The model name should not include the ":free" suffix.
        return new OpenRouterRequest("deepseek/deepseek-chat-v3.1:free", messages);
    }
}