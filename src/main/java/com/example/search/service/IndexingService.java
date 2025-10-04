package com.example.search.service;


import com.example.search.model.WebPage;
import com.example.search.repository.WebPageRepository;
import jakarta.annotation.PostConstruct;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

@Service
public class IndexingService {

    private static final String INDEX_DIR = "./lucene-index";
    private final WebPageRepository webPageRepository;
    private Directory indexDirectory;

    public IndexingService(WebPageRepository webPageRepository) {
        this.webPageRepository = webPageRepository;
    }

    @PostConstruct
    public void init() throws IOException {
        this.indexDirectory = FSDirectory.open(Paths.get(INDEX_DIR));
        // Optional: Clear the index on startup and re-index everything
        // reIndexAll();
    }

    @Transactional(readOnly = true)
    public void reIndexAll() {
        System.out.println("Starting to re-index all pages from database...");
        try (IndexWriter writer = createWriter()) {
            writer.deleteAll(); // Clear existing index

            // Use a try-with-resources block to ensure the stream is closed
            try (Stream<WebPage> pages = webPageRepository.findAll().stream()) {
                final int[] count = {0}; // Use an array to count inside the lambda
                pages.forEach(page -> {
                    try {
                        Document doc = new Document();
                        doc.add(new StringField("url", page.getUrl(), Field.Store.YES));
                        doc.add(new TextField("title", page.getTitle(), Field.Store.YES));
                        // Use the recommended method of storing content for RAG
                        doc.add(new TextField("content", page.getContent(), Field.Store.YES));
                        writer.addDocument(doc);
                        count[0]++;
                    } catch (IOException e) {
                        System.err.println("Error indexing page " + page.getUrl() + ": " + e.getMessage());
                    }
                });
                System.out.println("Finished re-indexing " + count[0] + " pages.");
            }
        } catch (IOException e) {
            System.err.println("Error re-indexing all pages: " + e.getMessage());
        }
    }

    private IndexWriter createWriter() throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        return new IndexWriter(indexDirectory, config);
    }
}

