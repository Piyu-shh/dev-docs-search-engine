# Vector Database RAG System - Setup Guide

## Overview

This document explains how to set up and use the new **Vector Database RAG System** with:
- **Local Gemma 3.1B LLM** via Ollama
- **HNSW Algorithm** for vector similarity search
- **Redis** for distributed caching
- **Custom Vector Database** for semantic search

## System Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    Spring Boot Application                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────────┐         ┌──────────────────┐             │
│  │   Chat Service   │         │ Embeddings Service             │
│  │  (Gemma via      │         │  (Ollama)        │             │
│  │   Ollama)        │         │                  │             │
│  └──────────────────┘         └──────────────────┘             │
│           │                            │                        │
│           └────────────────┬───────────┘                        │
│                            ▼                                    │
│  ┌──────────────────────────────────────┐                      │
│  │   Vector Database Service (HNSW)     │                      │
│  │  - Semantic similarity search         │                      │
│  │  - K-nearest neighbors               │                      │
│  └──────────────────────────────────────┘                      │
│           │                                                    │
│           └────────────────┬───────────────── MongoDB ─────    │
│                            ▼                  (Store pages)    │
│  ┌──────────────────────────────────────┐                      │
│  │   Redis Cache Service                │                      │
│  │  - Vector cache                      │                      │
│  │  - Embedding cache                   │                      │
│  └──────────────────────────────────────┘                      │
│           │                                                    │
│           └────────────────────────────────────────────────    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
         │                              │
         ▼                              ▼
    Ollama API               Redis Container
  (Gemma, Embeddings)        (Port 6379)
```

## Prerequisites

1. **Java 17+** (already installed)
2. **Ollama** - Download from https://ollama.ai
3. **Docker** - For running Redis
4. **Python** (optional, for direct Ollama API testing)

## Step 1: Install and Configure Ollama

### Windows/Mac Installation
```bash
# Download Ollama from https://ollama.ai
# Install using the installer
```

### Download Required Models

```bash
# Install Gemma 3.1B (LLM for chat)
ollama pull gemma:3.1b

# Install embedding model (for generating vector embeddings)
ollama pull nomic-embed-text

# Or use alternative embedding models
ollama pull mxbai-embed-large
ollama pull all-minilm
```

You can check available models at: https://ollama.ai/library

### Start Ollama Server

```bash
# Ollama runs as a background service on port 11434
ollama serve
```

**Verify Ollama is running:**
```bash
curl http://localhost:11434/api/tags
```

You should see a list of your downloaded models.

## Step 2: Start Redis Using Docker

### Prerequisites
- Docker Desktop installed and running

### Start Redis Container

```bash
# From the project root directory, run:
docker-compose up -d

# Verify Redis is running:
docker ps | grep redis

# Test Redis connection:
redis-cli ping
# Should return: PONG
```

If Docker is not available, install Redis locally:
- **Windows**: Download from https://github.com/microsoftarchive/redis/releases
- **Mac**: `brew install redis`
- **Linux**: `sudo apt-get install redis-server`

Then start Redis:
```bash
redis-server
```

## Step 3: Update Application Configuration

Update `src/main/resources/application.properties`:

```properties
# ===== Ollama Configuration =====
ollama.api.url=http://localhost:11434
ollama.embedding.model=nomic-embed-text
ollama.chat.model=gemma:3.1b

# ===== Redis Configuration =====
spring.redis.host=localhost
spring.redis.port=6379
spring.redis.password=
```

If you're using a remote Ollama or Redis, update the URLs accordingly.

## Step 4: Build and Run the Application

```bash
# Build the project
mvn clean package

# Run the application
mvn spring-boot:run

# Or run the JAR
java -jar target/search-0.0.1-SNAPSHOT.jar
```

The application will start on `http://localhost:8080`

## API Endpoints

### 1. **Crawl Websites**
```bash
GET /api/crawl?startUrl=https://example.com&depth=2
```

### 2. **Index Pages** (Both Lucene + Vector DB)
```bash
GET /api/index
```

This endpoint:
- Re-indexes all crawled pages in Lucene (full-text search)
- Generates embeddings for all pages
- Stores vectors in the HNSW-based Vector Database
- Caches vectors in Redis

### 3. **Search (Full-Text)**
```bash
GET /api/search?q=machine+learning
```

Returns Lucene full-text search results.

### 4. **Ask (Semantic Search + RAG)**
```bash
GET /api/ask?q=What+is+machine+learning?
```

**Flow:**
1. Generates embedding for the question
2. Searches Vector Database for top 5 similar documents (HNSW)
3. Retrieves content from MongoDB
4. Sends to Gemma with context
5. Returns AI-generated answer with sources

### 5. **Vector DB Statistics**
```bash
GET /api/vector-db/stats
```

Returns the number of vectors currently in the database.

### 6. **Clear Vector DB Cache**
```bash
GET /api/vector-db/clear
```

Clears all cached vectors and embeddings.

### 7. **Health Check**
```bash
GET /api/health
```

## How the System Works

### Indexing Process
```
Crawled Page (MongoDB)
         │
         ▼
Generate Embedding (Ollama)
         │
         ▼
Create Vector (ID, embedding, content, URL, title)
         │
    ┌────┴────┐
    ▼         ▼
Cache in   Add to
Redis    Vector DB (HNSW)
```

### RAG Query Process
```
User Question
     │
     ▼
Generate Question Embedding (Ollama)
     │
     ▼
Search Vector DB (HNSW - K-nearest neighbors)
     │
     ▼
Retrieve Top 5 Similar Documents
     │
     ▼
Fetch Full Content from MongoDB
     │
     ▼
Build Context String
     │
     ▼
Send to Gemma with Context (Ollama)
     │
     ▼
Return Answer + Source URLs
```

## Performance Tuning

### HNSW Parameters (in `VectorDatabaseService.java`)

```java
private static final int MAX_CONNECTIONS = 16;  // Max neighbors per node
private static final int EF = 200;              // Search parameter
private static final int EF_CONSTRUCTION = 200; // Construction parameter
```

**Tuning Tips:**
- Increase `MAX_CONNECTIONS` for better accuracy (slower indexing)
- Increase `EF` for better search accuracy (slower searches)
- Use `EF_CONSTRUCTION` higher for better initial build

### Redis Caching
- TTL set to 24 hours (configurable in `application.properties`)
- Automatically invalidates old cached vectors
- Monitor Redis memory with: `redis-cli INFO memory`

### Embedding Model Choices

| Model | Speed | Quality | Size | Recommended |
|-------|-------|---------|------|-------------|
| all-minilm | Fast | Good | 33M | ✓ For speed |
| nomic-embed-text | Balanced | Excellent | 274M | ✓ **Recommended** |
| mxbai-embed-large | Slow | Best | 1.3G | ✓ For accuracy |

## Troubleshooting

### Issue: "Error generating embedding"
- **Check:** Is Ollama running? `curl http://localhost:11434/api/tags`
- **Fix:** Start Ollama: `ollama serve`

### Issue: "Redis connection timeout"
- **Check:** Is Redis running? `redis-cli ping`
- **Fix:** Start Redis with `docker-compose up -d`

### Issue: "Gemma model not found"
- **Check:** `ollama list`
- **Fix:** Pull the model: `ollama pull gemma:3.1b`

### Issue: "Vector DB is empty after indexing"
- **Check:** MongoDB has crawled pages
- **Fix:** Crawl websites first: `GET /api/crawl?startUrl=...`

### Issue: "Slow embeddings generation"
- **Problem:** Using `mxbai-embed-large` (1.3GB, slower)
- **Solution:** Switch to `nomic-embed-text` (274MB, faster)

## Monitoring

### View Redis Cache Content
```bash
redis-cli
> KEYS vector:*
> GET vector:<sample-id>
> DBSIZE
```

### Monitor Ollama
```bash
# Check active processes
ollama list

# View model details
ollama show gemma:3.1b
```

### Application Logs
```bash
# The application logs vector indexing progress
tail -f <application.log>
```

## Advanced Usage

### Custom Vector Search Query
```java
// In RAGService
float[] queryEmbedding = embeddingsService.generateEmbedding("your query");
List<VectorDto> results = vectorDatabaseService.searchSimilar(queryEmbedding, 10);
```

### Batch Indexing
```bash
# POST to a custom endpoint (not yet available, extend CrawlController)
POST /api/batch-index
{
  "urls": ["url1", "url2", "url3"]
}
```

### Custom Similarity Metrics
Edit `computeCosineDistance()` in `VectorDatabaseService.java`:
- Current: Cosine similarity
- Alternative: Euclidean distance, Manhattan distance, etc.

## Production Considerations

1. **Scaling**
   - Use Redis Cluster for multiple instances
   - Deploy Ollama on separate GPU-enabled server
   - Use load balancing for multiple Spring Boot instances

2. **Monitoring**
   - Add Prometheus metrics for Vector DB operations
   - Monitor Redis memory and eviction policies
   - Track Ollama API latency

3. **Security**
   - Add Redis password authentication
   - Use Ollama with reverse proxy (nginx)
   - Implement API rate limiting

4. **Data Persistence**
   - Regular backups of MongoDB
   - Redis persistence enabled (AOF mode)
   - Vector database can be rebuilt from MongoDB

## Next Steps

1. Crawl your first website
2. Index the pages (both Lucene and Vector DB)
3. Test with semantic search queries
4. Monitor performance and tune HNSW parameters
5. Scale as needed

## References

- [Ollama Documentation](https://github.com/jmorganca/ollama)
- [HNSW Algorithm Paper](https://arxiv.org/abs/1802.02413)
- [Redis Documentation](https://redis.io/documentation)
- [Hnswlib (Java Bindings)](https://github.com/jelmerk/hnswlib)
