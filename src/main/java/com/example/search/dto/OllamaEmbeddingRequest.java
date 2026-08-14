package com.example.search.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class OllamaEmbeddingRequest implements Serializable {
    private String model;
    private String input;
    private String prompt; // For legacy support
}

@Data
@AllArgsConstructor
@NoArgsConstructor
class OllamaEmbeddingResponse {
    private List<Float> embedding;
    @JsonProperty("model")
    private String model;
}
