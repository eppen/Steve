package com.steve.ai.llm.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * LRU cache for LLM responses (JDK-only, no external dependencies).
 */
public class LLMCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(LLMCache.class);

    private static final int MAX_CACHE_SIZE = 500;
    private static final long TTL_MS = 5 * 60 * 1000L;

    private final Map<String, CacheEntry> cache = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        }
    );

    private long hitCount = 0;
    private long missCount = 0;
    private long evictionCount = 0;

    public LLMCache() {
        LOGGER.info("Initializing LLM cache (max size: {}, TTL: 5 minutes)", MAX_CACHE_SIZE);
    }

    public Optional<LLMResponse> get(String prompt, String model, String providerId) {
        String key = generateKey(prompt, model, providerId);
        CacheEntry entry;

        synchronized (cache) {
            entry = cache.get(key);
            if (entry != null && entry.isExpired()) {
                cache.remove(key);
                entry = null;
                evictionCount++;
            }
            if (entry != null) {
                hitCount++;
                LOGGER.debug("Cache HIT for provider={}, model={}, promptHash={}",
                    providerId, model, key.substring(0, 8));
                return Optional.of(entry.response);
            }
            missCount++;
        }

        LOGGER.debug("Cache MISS for provider={}, model={}, promptHash={}",
            providerId, model, key.substring(0, 8));
        return Optional.empty();
    }

    public void put(String prompt, String model, String providerId, LLMResponse response) {
        String key = generateKey(prompt, model, providerId);
        LLMResponse cachedResponse = response.withCacheFlag(true);

        synchronized (cache) {
            purgeExpiredLocked();
            cache.put(key, new CacheEntry(cachedResponse, System.currentTimeMillis() + TTL_MS));
        }

        LOGGER.debug("Cached response for provider={}, model={}, promptHash={}, tokens={}",
            providerId, model, key.substring(0, 8), response.getTokensUsed());
    }

    private void purgeExpiredLocked() {
        cache.entrySet().removeIf(e -> {
            if (e.getValue().isExpired()) {
                evictionCount++;
                return true;
            }
            return false;
        });
    }

    private String generateKey(String prompt, String model, String providerId) {
        return sha256Hex(providerId + ":" + model + ":" + prompt);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public CacheStats getStats() {
        synchronized (cache) {
            return new CacheStats(hitCount, missCount, evictionCount);
        }
    }

    public long size() {
        synchronized (cache) {
            purgeExpiredLocked();
            return cache.size();
        }
    }

    public void clear() {
        synchronized (cache) {
            long sizeBefore = cache.size();
            cache.clear();
            LOGGER.info("Cache cleared, removed ~{} entries", sizeBefore);
        }
    }

    public void logStats() {
        CacheStats stats = getStats();
        LOGGER.info("LLM Cache Stats - Size: ~{}/{}, Hit Rate: {}%, Hits: {}, Misses: {}, Evictions: {}",
            size(),
            MAX_CACHE_SIZE,
            String.format("%.2f", stats.hitRate() * 100),
            stats.hitCount(),
            stats.missCount(),
            stats.evictionCount()
        );
    }

    public record CacheStats(long hitCount, long missCount, long evictionCount) {
        public double hitRate() {
            long total = hitCount + missCount;
            return total == 0 ? 0.0 : (double) hitCount / total;
        }

        public double missRate() {
            return 1.0 - hitRate();
        }
    }

    private static final class CacheEntry {
        private final LLMResponse response;
        private final long expiresAtMs;

        private CacheEntry(LLMResponse response, long expiresAtMs) {
            this.response = response;
            this.expiresAtMs = expiresAtMs;
        }

        private boolean isExpired() {
            return System.currentTimeMillis() >= expiresAtMs;
        }
    }
}
