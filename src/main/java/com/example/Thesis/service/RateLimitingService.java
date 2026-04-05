package com.example.Thesis.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@Slf4j
public class RateLimitingService {

    private final Bucket globalBucket;

    public RateLimitingService(
            @Value("${rate-limit.global.capacity:50}") int capacity,
            @Value("${rate-limit.global.refill-tokens:50}") int refillTokens,
            @Value("${rate-limit.global.refill-duration-ms:1000}") long refillDurationMs) {

        log.info("RateLimitingService initializing with capacity: {}, refill: {} tokens per {} ms",
                capacity, refillTokens, refillDurationMs);

        // Algoritma: Belirli sürede (refillDurationMs) bir miktar (refillTokens) token yenilenir.
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(refillTokens, Duration.ofMillis(refillDurationMs))
                .build();

        // Sisteme gelen tüm isteklerin tamamını kapsayan global "Bucket" (kova).
        this.globalBucket = Bucket.builder()
                .addLimit(limit)
                .build();
    }

    /**
     * İstek işleme kapasitesi kaldıysa (token varsa) true döner ve 1 token tüketir.
     * Kapasite tamamen doluysa false döner.
     */
    public boolean tryConsume() {
        return globalBucket.tryConsume(1);
    }
}
