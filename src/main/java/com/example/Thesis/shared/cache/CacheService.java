package com.example.Thesis.shared.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * Generic cache service interface following the Dependency Inversion Principle.
 * Allows swapping Redis with any other cache without touching business logic.
 */
public interface CacheService {
    <T> void set(String key, T value, Duration ttl);
    <T> Optional<T> get(String key, Class<T> type);
    void delete(String key);
    boolean exists(String key);
    long deleteByPattern(String pattern);
}
