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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

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
        Path path = Paths.get(INDEX_DIR);
        Files.createDirectories(path);
        this.indexDirectory = FSDirectory.open(path);
    }

    public void reIndexAll() {
        System.out.println("Starting to re-index all pages in Lucene...");

        try (IndexWriter writer = createWriter()) {
            writer.deleteAll(); // Clear existing index

            List<WebPage> pages = webPageRepository.findAll();
            int count = 0;
            for (WebPage page : pages) {
                if (page.getUrl() == null || page.getUrl().trim().isEmpty()) {
                    continue;
                }
                try {
                    Document doc = new Document();
                    doc.add(new StringField("url", page.getUrl().trim(), Field.Store.YES));
                    doc.add(new TextField("title", page.getTitle() != null ? page.getTitle() : "", Field.Store.YES));
                    doc.add(new TextField("content", page.getContent() != null ? page.getContent() : "", Field.Store.YES));
                    writer.addDocument(doc);
                    count++;
                } catch (IOException e) {
                    System.err.println("Error indexing page in Lucene " + page.getUrl() + ": " + e.getMessage());
                }
            }
            writer.commit();
            System.out.println("Finished re-indexing " + count + " pages in Lucene.");
        } catch (IOException e) {
            System.err.println("Error re-indexing all pages: " + e.getMessage());
        }

        System.out.println("Lucene re-indexing complete.");
    }

    private IndexWriter createWriter() throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        config.setRAMBufferSizeMB(16.0);
        return new IndexWriter(indexDirectory, config);
    }
}

