package com.example.search.dto;

import java.util.List;

public record OpenRouterRequest(String model, List<Message> messages) {}