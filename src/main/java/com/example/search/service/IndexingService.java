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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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
    private final RAGService ragService;
    private Directory indexDirectory;


    @Autowired @Lazy
    private IndexingService self;

    public IndexingService(WebPageRepository webPageRepository, RAGService ragService) {
        this.webPageRepository = webPageRepository;
        this.ragService = ragService;
    }

    @PostConstruct
    public void init() throws IOException {
        this.indexDirectory = FSDirectory.open(Paths.get(INDEX_DIR));
    }

    @Transactional(readOnly = true)
    public void reIndexAll() {
        System.out.println("Starting to re-index all pages...");
        
        // Step 1: Re-index in Lucene for full-text search
        try (IndexWriter writer = createWriter()) {
            writer.deleteAll(); // Clear existing index

            try (Stream<WebPage> pages = webPageRepository.findAll().stream()) {
                final int[] count = {0};
                pages.forEach(page -> {
                    try {
                        Document doc = new Document();
                        doc.add(new StringField("url", page.getUrl(), Field.Store.YES));
                        doc.add(new TextField("title", page.getTitle(), Field.Store.YES));
                        doc.add(new TextField("content", page.getContent(), Field.Store.YES));
                        writer.addDocument(doc);
                        count[0]++;
                    } catch (IOException e) {
                        System.err.println("Error indexing page in Lucene " + page.getUrl() + ": " + e.getMessage());
                    }
                });
                System.out.println("Finished re-indexing " + count[0] + " pages in Lucene.");
            }
        } catch (IOException e) {
            System.err.println("Error re-indexing all pages: " + e.getMessage());
        }

        System.out.println("Lucene re-indexing complete. Vector database indexing will happen dynamically during RAG queries.");
    }

    private IndexWriter createWriter() throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        IndexWriterConfig indexWriterConfig = config.setRAMBufferSizeMB(16.0);
        return new IndexWriter(indexDirectory, config);
    }
}

