package com.example.search.service;

import com.example.search.dto.SearchResultDto;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
public class SearchService {

    private static final String INDEX_DIR = "./lucene-index";
    private final int maxResults;

    public SearchService(@Value("${lucene.results.count:10}") int maxResults) {
        this.maxResults = maxResults;
    }

    public List<SearchResultDto> search(String queryStr) {
        List<SearchResultDto> results = new ArrayList<>();
        if (queryStr == null || queryStr.trim().isEmpty()) {
            return results;
        }

        Path indexPath = Paths.get(INDEX_DIR);
        if (!Files.exists(indexPath)) {
            return results;
        }

        try (Directory indexDirectory = FSDirectory.open(indexPath)) {
            if (!DirectoryReader.indexExists(indexDirectory)) {
                return results;
            }

            try (IndexReader reader = DirectoryReader.open(indexDirectory)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                String[] fields = {"title", "content"};
                StandardAnalyzer analyzer = new StandardAnalyzer();
                MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, analyzer);
                parser.setDefaultOperator(QueryParser.Operator.OR);

                Query query;
                try {
                    query = parser.parse(queryStr.trim());
                } catch (ParseException e) {
                    // Fall back to escaped query if user entered special Lucene characters
                    query = parser.parse(QueryParser.escape(queryStr.trim()));
                }

                TopDocs topDocs = searcher.search(query, maxResults);

                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                    Document doc = searcher.storedFields().document(scoreDoc.doc);
                    String url = doc.get("url");
                    String title = doc.get("title");
                    if (url != null) {
                        results.add(new SearchResultDto(url, title != null ? title : ""));
                    }
                }
            }
        } catch (IOException | ParseException e) {
            System.err.println("Error during search: " + e.getMessage());
        }
        return results;
    }
}