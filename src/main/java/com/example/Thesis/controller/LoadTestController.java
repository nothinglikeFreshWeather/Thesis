package com.example.Thesis.controller;

import com.example.Thesis.dto.StockEventDto;
import com.example.Thesis.service.KafkaProducerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/load-test")
@RequiredArgsConstructor
@Slf4j
public class LoadTestController {

    private final KafkaProducerService kafkaProducerService;

    /**
     * Load test endpoint - sends N messages to Kafka as fast as possible
     * POST /api/load-test/kafka?count=10000
     */
    @PostMapping("/kafka")
    public ResponseEntity<Map<String, Object>> kafkaLoadTest(
            @RequestParam(defaultValue = "10000") int count) {

        log.info("🚀 Starting Kafka load test with {} messages", count);

        long startTime = System.currentTimeMillis();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        // Send messages in parallel
        CompletableFuture<Void> allFutures = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < count; i++) {
                try {
                    // Create test event
                    StockEventDto event = StockEventDto.create(
                            StockEventDto.EventType.CREATED,
                            (long) (i + 1),
                            "LoadTest-Product-" + i,
                            100,
                            new BigDecimal("99.99"));

                    // Send to Kafka (async)
                    kafkaProducerService.sendStockEvent(event);
                    successCount.incrementAndGet();

                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    log.error("Failed to send message {}: {}", i, e.getMessage());
                }

                // Log progress every 1000 messages
                if ((i + 1) % 1000 == 0) {
                    log.info("Progress: {}/{} messages sent", i + 1, count);
                }
            }
        });

        // Wait for completion (with timeout)
        try {
            allFutures.get();
        } catch (Exception e) {
            log.error("Load test interrupted: {}", e.getMessage());
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double throughput = (count * 1000.0) / duration; // messages per second

        Map<String, Object> result = new HashMap<>();
        result.put("totalMessages", count);
        result.put("successCount", successCount.get());
        result.put("failureCount", failureCount.get());
        result.put("durationMs", duration);
        result.put("throughputMsgPerSec", Math.round(throughput));
        result.put("status", "completed");

        log.info("✅ Load test completed: {} messages in {}ms ({} msg/sec)",
                count, duration, Math.round(throughput));

        return ResponseEntity.ok(result);
    }

    /**
     * Batch load test - sends messages in batches with control
     * POST /api/load-test/kafka/batch?count=10000&batchSize=100&delayMs=10
     */
    @PostMapping("/kafka/batch")
    public ResponseEntity<Map<String, Object>> kafkaBatchLoadTest(
            @RequestParam(defaultValue = "10000") int count,
            @RequestParam(defaultValue = "100") int batchSize,
            @RequestParam(defaultValue = "0") long delayMs) {

        log.info("🚀 Starting Kafka batch load test: {} messages, batch size: {}, delay: {}ms",
                count, batchSize, delayMs);

        long startTime = System.currentTimeMillis();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        int batches = (int) Math.ceil((double) count / batchSize);

        CompletableFuture<Void> allFutures = CompletableFuture.runAsync(() -> {
            for (int batch = 0; batch < batches; batch++) {
                int batchStart = batch * batchSize;
                int batchEnd = Math.min(batchStart + batchSize, count);

                for (int i = batchStart; i < batchEnd; i++) {
                    try {
                        StockEventDto event = StockEventDto.create(
                                StockEventDto.EventType.CREATED,
                                (long) (i + 1),
                                "BatchTest-Product-" + i,
                                100,
                                new BigDecimal("99.99"));

                        kafkaProducerService.sendStockEvent(event);
                        successCount.incrementAndGet();

                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    }
                }

                // Delay between batches
                if (delayMs > 0 && batch < batches - 1) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                log.info("Batch {}/{} completed", batch + 1, batches);
            }
        });

        try {
            allFutures.get();
        } catch (Exception e) {
            log.error("Batch load test interrupted: {}", e.getMessage());
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double throughput = (count * 1000.0) / duration;

        Map<String, Object> result = new HashMap<>();
        result.put("totalMessages", count);
        result.put("batchSize", batchSize);
        result.put("batches", batches);
        result.put("successCount", successCount.get());
        result.put("failureCount", failureCount.get());
        result.put("durationMs", duration);
        result.put("throughputMsgPerSec", Math.round(throughput));
        result.put("status", "completed");

        log.info("✅ Batch load test completed: {} messages in {}ms ({} msg/sec)",
                count, duration, Math.round(throughput));

        return ResponseEntity.ok(result);
    }

    /**
     * Health check
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> status = new HashMap<>();
        status.put("status", "UP");
        status.put("service", "LoadTestController");
        return ResponseEntity.ok(status);
    }
}
