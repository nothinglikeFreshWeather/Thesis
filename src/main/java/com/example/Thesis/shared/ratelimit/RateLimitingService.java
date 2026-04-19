package com.example.Thesis.shared.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Global API rate limiter using the Token Bucket algorithm (Bucket4j).
 *
 * <p>Applied to all {@code /api/**} endpoints via {@link RateLimitInterceptor}.
 * Configured via {@code rate-limit.global.*} in application.yaml.</p>
 */
@Service
@Slf4j
public class RateLimitingService {

    private final Bucket globalBucket;

    public RateLimitingService(
            @Value("${rate-limit.global.capacity:50}") int capacity,
            @Value("${rate-limit.global.refill-tokens:50}") int refillTokens,
            @Value("${rate-limit.global.refill-duration-ms:1000}") long refillDurationMs) {

        log.info("RateLimitingService: capacity={}, refill={} tokens/{}ms",
                capacity, refillTokens, refillDurationMs);

        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(refillTokens, Duration.ofMillis(refillDurationMs))
                .build();

        this.globalBucket = Bucket.builder().addLimit(limit).build();
    }

    /** Returns {@code true} and consumes 1 token if capacity is available. */
    public boolean tryConsume() {
        return globalBucket.tryConsume(1);
    }
}
