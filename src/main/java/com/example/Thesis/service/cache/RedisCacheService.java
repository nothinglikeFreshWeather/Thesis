package com.example.Thesis.service.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Redis implementation of CacheService.
 * Handles master-replica configuration for AP testing:
 * - Writes go to master (via Toxiproxy)
 * - Reads can come from replica (via Toxiproxy)
 * - Graceful degradation when Redis is unavailable
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisCacheService implements CacheService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public <T> void set(String key, T value, Duration ttl) {
        try {
            String jsonValue = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, jsonValue, ttl);
            log.debug("Cache SET: key={}, ttl={}", key, ttl);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize value for key: {}", key, e);
            throw new RuntimeException("Cache serialization failed", e);
        } catch (Exception e) {
            // Graceful degradation - log but don't fail the operation
            log.warn("Failed to set cache key: {} - {}", key, e.getMessage());
        }
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        try {
            String jsonValue = redisTemplate.opsForValue().get(key);

            if (jsonValue == null) {
                log.debug("Cache MISS: key={}", key);
                return Optional.empty();
            }

            T value = objectMapper.readValue(jsonValue, type);
            log.debug("Cache HIT: key={}", key);
            return Optional.of(value);

        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize value for key: {}", key, e);
            return Optional.empty();
        } catch (Exception e) {
            // Graceful degradation - treat as cache miss
            log.warn("Failed to get cache key: {} - {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void delete(String key) {
        try {
            Boolean deleted = redisTemplate.delete(key);
            log.debug("Cache DELETE: key={}, deleted={}", key, deleted);
        } catch (Exception e) {
            log.warn("Failed to delete cache key: {} - {}", key, e.getMessage());
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            Boolean exists = redisTemplate.hasKey(key);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.warn("Failed to check cache key existence: {} - {}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public long deleteByPattern(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                Long deleted = redisTemplate.delete(keys);
                log.debug("Cache DELETE by pattern: pattern={}, deleted={}", pattern, deleted);
                return deleted != null ? deleted : 0;
            }
            return 0;
        } catch (Exception e) {
            log.warn("Failed to delete cache keys by pattern: {} - {}", pattern, e.getMessage());
            return 0;
        }
    }
}
