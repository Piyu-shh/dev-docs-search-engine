package com.example.search.service;

import com.example.search.dto.VectorDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class VectorCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private static final String VECTOR_CACHE_PREFIX = "vector:";
    private static final String EMBEDDING_CACHE_PREFIX = "embedding:";
    private static final long CACHE_TTL_HOURS = 24;

    public VectorCacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Cache a vector with TTL (Time To Live)
     */
    public void cacheVector(VectorDto vector) {
        if (vector == null || vector.getId() == null) {
            return;
        }

        try {
            String key = VECTOR_CACHE_PREFIX + vector.getId();
            redisTemplate.opsForValue().set(
                    key,
                    objectMapper.writeValueAsString(vector),
                    CACHE_TTL_HOURS,
                    TimeUnit.HOURS
            );
        } catch (Exception e) {
            System.err.println("Error caching vector: " + e.getMessage());
        }
    }

    /**
     * Get vector from cache
     */
    public VectorDto getVectorFromCache(String vectorId) {
        try {
            String key = VECTOR_CACHE_PREFIX + vectorId;
            Object cached = redisTemplate.opsForValue().get(key);
            
            if (cached != null) {
                return objectMapper.readValue(cached.toString(), VectorDto.class);
            }
        } catch (Exception e) {
            System.err.println("Error retrieving vector from cache: " + e.getMessage());
        }
        return null;
    }

    /**
     * Cache embedding (float array)
     */
    public void cacheEmbedding(String text, float[] embedding) {
        if (text == null || embedding == null) {
            return;
        }

        try {
            String key = EMBEDDING_CACHE_PREFIX + hashText(text);
            StringBuilder sb = new StringBuilder();
            for (float f : embedding) {
                sb.append(f).append(",");
            }
            if (sb.length() > 0) {
                sb.deleteCharAt(sb.length() - 1);
            }

            redisTemplate.opsForValue().set(
                    key,
                    sb.toString(),
                    CACHE_TTL_HOURS,
                    TimeUnit.HOURS
            );
        } catch (Exception e) {
            System.err.println("Error caching embedding: " + e.getMessage());
        }
    }

    /**
     * Get embedding from cache
     */
    public float[] getEmbeddingFromCache(String text) {
        try {
            String key = EMBEDDING_CACHE_PREFIX + hashText(text);
            Object cached = redisTemplate.opsForValue().get(key);

            if (cached != null) {
                String[] parts = cached.toString().split(",");
                float[] embedding = new float[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    embedding[i] = Float.parseFloat(parts[i]);
                }
                return embedding;
            }
        } catch (Exception e) {
            System.err.println("Error retrieving embedding from cache: " + e.getMessage());
        }
        return null;
    }

    /**
     * Invalidate vector cache
     */
    public void invalidateVector(String vectorId) {
        try {
            String key = VECTOR_CACHE_PREFIX + vectorId;
            redisTemplate.delete(key);
        } catch (Exception e) {
            System.err.println("Error invalidating vector cache: " + e.getMessage());
        }
    }

    /**
     * Invalidate embedding cache
     */
    public void invalidateEmbedding(String text) {
        try {
            String key = EMBEDDING_CACHE_PREFIX + hashText(text);
            redisTemplate.delete(key);
        } catch (Exception e) {
            System.err.println("Error invalidating embedding cache: " + e.getMessage());
        }
    }

    /**
     * Check if vector exists in cache
     */
    public boolean vectorExistsInCache(String vectorId) {
        try {
            String key = VECTOR_CACHE_PREFIX + vectorId;
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            System.err.println("Error checking vector cache: " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if embedding exists in cache
     */
    public boolean embeddingExistsInCache(String text) {
        try {
            String key = EMBEDDING_CACHE_PREFIX + hashText(text);
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            System.err.println("Error checking embedding cache: " + e.getMessage());
            return false;
        }
    }

    /**
     * Clear all vector and embedding cache keys without flushing the entire Redis database
     */
    public void clearAllVectorCache() {
        try {
            java.util.Set<String> vectorKeys = redisTemplate.keys(VECTOR_CACHE_PREFIX + "*");
            if (vectorKeys != null && !vectorKeys.isEmpty()) {
                redisTemplate.delete(vectorKeys);
            }
            java.util.Set<String> embeddingKeys = redisTemplate.keys(EMBEDDING_CACHE_PREFIX + "*");
            if (embeddingKeys != null && !embeddingKeys.isEmpty()) {
                redisTemplate.delete(embeddingKeys);
            }
            System.out.println("Vector and embedding cache keys cleared");
        } catch (Exception e) {
            System.err.println("Error clearing cache: " + e.getMessage());
        }
    }

    /**
     * Hash text for use as cache key using SHA-256
     */
    private String hashText(String text) {
        if (text == null) return "";
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
