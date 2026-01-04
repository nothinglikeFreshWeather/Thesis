package com.example.Thesis.service.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * Generic cache service interface following Dependency Inversion Principle.
 * Allows swapping Redis with other cache implementations without changing
 * business logic.
 */
public interface CacheService {

    /**
     * Store a value in cache with TTL
     * 
     * @param key   Cache key
     * @param value Value to store
     * @param ttl   Time to live
     * @param <T>   Value type
     */
    <T> void set(String key, T value, Duration ttl);

    /**
     * Retrieve a value from cache
     * 
     * @param key  Cache key
     * @param type Expected value type
     * @param <T>  Value type
     * @return Optional containing value if found, empty otherwise
     */
    <T> Optional<T> get(String key, Class<T> type);

    /**
     * Delete a key from cache
     * 
     * @param key Cache key to delete
     */
    void delete(String key);

    /**
     * Check if key exists in cache
     * 
     * @param key Cache key
     * @return true if exists, false otherwise
     */
    boolean exists(String key);

    /**
     * Delete multiple keys matching a pattern
     * 
     * @param pattern Key pattern (e.g., "stock:*")
     * @return Number of keys deleted
     */
    long deleteByPattern(String pattern);
}
