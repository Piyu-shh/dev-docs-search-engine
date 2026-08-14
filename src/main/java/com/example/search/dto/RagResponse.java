package com.example.search.dto;

import java.util.List;

public record RagResponse(String answer, List<SearchResultDto> sources) {}