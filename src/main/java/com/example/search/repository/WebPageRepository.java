package com.example.search.repository;

import com.example.search.model.WebPage;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.Optional;

public interface WebPageRepository extends MongoRepository<WebPage, String> {

    // Custom method to check if a URL has already been crawled
    Optional<WebPage> findByUrl(String url);
}
