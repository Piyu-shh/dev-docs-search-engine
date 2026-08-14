package com.example.search.service;


import com.example.search.dto.SearchResultDto;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
public class SearchService {

    private static final String INDEX_DIR = "./lucene-index";
    private static final int MAX_RESULTS = 10;

    public List<SearchResultDto> search(String queryStr) {
        List<SearchResultDto> results = new ArrayList<>();
        try {
            Directory indexDirectory = FSDirectory.open(Paths.get(INDEX_DIR));
            IndexReader reader = DirectoryReader.open(indexDirectory);
            IndexSearcher searcher = new IndexSearcher(reader);

            // The query parser must use the same analyzer as the indexer
            QueryParser parser = new QueryParser("content", new StandardAnalyzer());

            String fuzzyQueryString = queryStr + "~1";
            Query query = parser.parse(fuzzyQueryString);

            TopDocs topDocs = searcher.search(query, MAX_RESULTS);

            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.doc(scoreDoc.doc);
                results.add(new SearchResultDto(
                        doc.get("url"),
                        doc.get("title")

                ));
            }
            reader.close();
        } catch (IOException | ParseException e) {
            System.err.println("Error during search: " + e.getMessage());
        }
        return results;
    }
}