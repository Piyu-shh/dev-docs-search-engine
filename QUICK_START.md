# Quick Start - Vector Database RAG System

## 🚀 Start the System (5 minutes)

### 1. Start Ollama
```bash
# Open terminal and run:
ollama serve

# In another terminal, pull models:
ollama pull gemma:3.1b
ollama pull nomic-embed-text
```

### 2. Start Redis
```bash
# From project root:
docker-compose up -d

# Verify:
redis-cli ping  # Should output: PONG
```

### 3. Build and Run Spring Boot App
```bash
mvn clean package
mvn spring-boot:run
```

App runs at: `http://localhost:8080`

---

## 📝 Usage Examples

### 1. Crawl a Website
```bash
curl "http://localhost:8080/api/crawl?startUrl=https://en.wikipedia.org/wiki/Artificial_intelligence&depth=1"
```

### 2. Index Pages (Lucene + Vector DB)
```bash
curl "http://localhost:8080/api/index"
```
⏳ *Wait for indexing to complete. Check console for "Finished re-indexing X pages in Vector Database"*

### 3. Ask a Question (RAG with Gemma)
```bash
curl "http://localhost:8080/api/ask?q=What+is+machine+learning?"
```

**Response:**
```json
{
  "answer": "Machine learning is a subset of artificial intelligence...",
  "sources": [
    {"url": "https://...", "title": "AI Wikipedia Page"}
  ]
}
```

### 4. Traditional Full-Text Search
```bash
curl "http://localhost:8080/api/search?q=neural+networks"
```

---

## 🔍 API Endpoints Summary

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/crawl` | GET | Crawl websites recursively |
| `/api/index` | GET | Index in Lucene + Vector DB |
| `/api/search` | GET | Full-text search (Lucene) |
| `/api/ask` | GET | Semantic RAG search (Vector DB + Gemma) |
| `/api/vector-db/stats` | GET | View vector DB size |
| `/api/vector-db/clear` | GET | Clear vector cache |
| `/api/health` | GET | Health check |

---

## 🛠️ Configuration

File: `src/main/resources/application.properties`

```properties
# Ollama
ollama.api.url=http://localhost:11434
ollama.embedding.model=nomic-embed-text
ollama.chat.model=gemma:3.1b

# Redis
spring.redis.host=localhost
spring.redis.port=6379
```

---

## ⚠️ Troubleshooting

| Problem | Solution |
|---------|----------|
| "Cannot connect to Ollama" | Run `ollama serve` |
| "Redis connection error" | Run `docker-compose up -d` |
| "Gemma model not found" | Run `ollama pull gemma:3.1b` |
| "Slow embeddings" | Check Ollama is using GPU (check `ollama serve` output) |

---

## 📊 System Overview

```
Your Question
    │
    ├─→ Generate embedding (Ollama)
    │
    ├─→ Search Vector DB (HNSW - finds 5 similar docs)
    │
    ├─→ Get full content from MongoDB
    │
    ├─→ Send to Gemma with context (Ollama)
    │
    └─→ Return answer + sources
```

---

## 🎯 What Gets Cached?

- ✅ **Embeddings** - Questions and documents (Redis)
- ✅ **Vectors** - Full vector data (Redis + Vector DB)
- ✅ **Pages** - Crawled content (MongoDB)
- ✅ **Lucene Index** - Full-text search (Disk: `./lucene-index/`)

All caches survive application restarts.

---

## 📚 For More Details

Read `VECTOR_DB_SETUP.md` for:
- Detailed architecture
- Performance tuning
- Production deployment
- Advanced usage
- Model selection guide

---

## 💡 Tips

1. **First crawl is slow** - Ollama needs to generate embeddings for every page
2. **Subsequent searches are fast** - Uses cached embeddings from Redis
3. **HNSW learns patterns** - Better results with more pages indexed
4. **Monitor with** - `redis-cli DBSIZE` to see cache size

---

## 🔄 Typical Workflow

```
1. Setup: ollama serve → docker-compose up → mvn spring-boot:run
2. Crawl: /api/crawl?startUrl=https://...
3. Index: /api/index (generates embeddings + vectors)
4. Ask: /api/ask?q=What+is+...?
5. Monitor: /api/vector-db/stats
```

**Enjoy semantic search! 🎉**
