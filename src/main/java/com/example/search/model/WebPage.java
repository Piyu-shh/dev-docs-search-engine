package com.example.search.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;

@Data
@Document(collection = "pages") // Specifies the MongoDB collection name
public class WebPage {

    @Id // Marks this field as the primary key
    private String id;

    private String url;
    private String title;
    private String content; // We'll store the parsed text content here
    private LocalDateTime crawledAt;

    public WebPage(String url, String title, String content) {
        this.url = url;
        this.title = title;
        this.content = content;
        this.crawledAt = LocalDateTime.now();
    }
}