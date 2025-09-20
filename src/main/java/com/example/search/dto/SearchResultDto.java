package com.example.search.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SearchResultDto {
    private String url;
    private String title;

}