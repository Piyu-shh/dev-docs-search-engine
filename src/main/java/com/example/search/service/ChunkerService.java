package com.example.search.service;

import org.apache.commons.text.StringTokenizer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ChunkerService {

    private static final int CHUNK_SIZE_CHARS = 5000; // 5000 character chunks for vector embeddings
    private static final int OVERLAP_CHARS = 500; // 500 character overlap between chunks

    /**
     * Split text into chunks of approximately 5000 characters
     */
    public List<String> chunkText(String text) {
        return chunkText(text, CHUNK_SIZE_CHARS, OVERLAP_CHARS);
    }

    /**
     * Split text into chunks with custom size and overlap (character-based)
     */
    public List<String> chunkText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();

        if (text == null || text.trim().isEmpty()) {
            return chunks;
        }

        // Character-based chunking
        if (text.length() <= chunkSize) {
            chunks.add(text);
            return chunks;
        }

        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            
            // Try to break at word boundary
            if (end < text.length()) {
                int lastSpace = text.lastIndexOf(' ', end);
                if (lastSpace > start + (chunkSize / 2)) {
                    end = lastSpace;
                }
            }

            chunks.add(text.substring(start, end).trim());

            // Move start with overlap, but ensure progress
            start = end - overlap;
            if (start <= end - overlap) {
                start = end;
            }
        }

        return chunks;
    }

    /**
     * Chunk a web page's content into multiple pieces with metadata
     */
    public List<TextChunk> chunkWebPage(String url, String title, String content) {
        List<String> textChunks = chunkText(content);
        List<TextChunk> result = new ArrayList<>();

        for (int i = 0; i < textChunks.size(); i++) {
            TextChunk chunk = new TextChunk(
                    url + "_chunk_" + i,
                    url,
                    title,
                    textChunks.get(i),
                    i,
                    textChunks.size()
            );
            result.add(chunk);
        }

        return result;
    }

    /**
     * Inner class to represent a text chunk with metadata
     */
    public static class TextChunk {
        private String chunkId;
        private String sourceUrl;
        private String title;
        private String text;
        private int chunkIndex;
        private int totalChunks;

        public TextChunk(String chunkId, String sourceUrl, String title, String text, int chunkIndex, int totalChunks) {
            this.chunkId = chunkId;
            this.sourceUrl = sourceUrl;
            this.title = title;
            this.text = text;
            this.chunkIndex = chunkIndex;
            this.totalChunks = totalChunks;
        }

        public String getChunkId() { return chunkId; }
        public String getSourceUrl() { return sourceUrl; }
        public String getTitle() { return title; }
        public String getText() { return text; }
        public int getChunkIndex() { return chunkIndex; }
        public int getTotalChunks() { return totalChunks; }

        @Override
        public String toString() {
            return "TextChunk{" +
                    "chunkId='" + chunkId + '\'' +
                    ", sourceUrl='" + sourceUrl + '\'' +
                    ", title='" + title + '\'' +
                    ", chunkIndex=" + chunkIndex +
                    ", totalChunks=" + totalChunks +
                    ", textLength=" + (text != null ? text.length() : 0) +
                    '}';
        }
    }
}
