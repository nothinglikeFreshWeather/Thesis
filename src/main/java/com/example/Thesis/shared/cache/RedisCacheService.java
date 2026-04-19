package com.example.Thesis.shared.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Redis implementation of {@link CacheService}.
 *
 * <p>Handles master-replica configuration for AP testing:
 * writes go to master (via Toxiproxy), reads can come from replica.
 * Gracefully degrades when Redis is unavailable.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisCacheService implements CacheService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;
    private final Timer cacheGetTimer;
    private final Timer cacheSetTimer;
    private final Timer cacheDeleteTimer;
    private final Counter redisReconnectCounter;

    @Override
    public <T> void set(String key, T value, Duration ttl) {
        cacheSetTimer.record(() -> {
            try {
                redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
                log.debug("Cache SET: key={}, ttl={}", key, ttl);
            } catch (JsonProcessingException e) {
                log.error("Serialization failed for key: {}", key, e);
                throw new RuntimeException("Cache serialization failed", e);
            } catch (Exception e) {
                redisReconnectCounter.increment();
                log.warn("Cache SET failed — degrading gracefully: key={}, error={}", key, e.getMessage());
            }
        });
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        return cacheGetTimer.record(() -> {
            try {
                String json = redisTemplate.opsForValue().get(key);
                if (json == null) {
                    cacheMissCounter.increment();
                    log.debug("Cache MISS: key={}", key);
                    return Optional.empty();
                }
                cacheHitCounter.increment();
                log.debug("Cache HIT: key={}", key);
                return Optional.of(objectMapper.readValue(json, type));
            } catch (JsonProcessingException e) {
                log.error("Deserialization failed for key: {}", key, e);
                cacheMissCounter.increment();
                return Optional.empty();
            } catch (Exception e) {
                redisReconnectCounter.increment();
                cacheMissCounter.increment();
                log.warn("Cache GET failed — fallback to DB: key={}, error={}", key, e.getMessage());
                return Optional.empty();
            }
        });
    }

    @Override
    public void delete(String key) {
        cacheDeleteTimer.record(() -> {
            try {
                redisTemplate.delete(key);
                log.debug("Cache DELETE: key={}", key);
            } catch (Exception e) {
                redisReconnectCounter.increment();
                log.warn("Cache DELETE failed: key={}, error={}", key, e.getMessage());
            }
        });
    }

    @Override
    public boolean exists(String key) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.warn("Cache EXISTS check failed: key={}", key);
            return false;
        }
    }

    @Override
    public long deleteByPattern(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                Long deleted = redisTemplate.delete(keys);
                return deleted != null ? deleted : 0;
            }
            return 0;
        } catch (Exception e) {
            log.warn("Cache DELETE by pattern failed: pattern={}", pattern);
            return 0;
        }
    }
}
