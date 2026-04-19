package com.example.Thesis.stock.controller;

import com.example.Thesis.stock.dto.StockEventDto;
import com.example.Thesis.stock.service.KafkaProducerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Load-test endpoints for Kafka throughput benchmarks.
 *
 * <p>These endpoints bypass the Outbox and send events directly and asynchronously
 * to Kafka — intentional for throughput testing.</p>
 */
@RestController
@RequestMapping("/api/load-test")
@RequiredArgsConstructor
@Slf4j
public class LoadTestController {

    private final KafkaProducerService kafkaProducerService;

    /** POST /api/load-test/kafka?count=10000 */
    @PostMapping("/kafka")
    public ResponseEntity<Map<String, Object>> kafkaLoadTest(
            @RequestParam(defaultValue = "10000") int count) {

        log.info("🚀 Kafka load test started: {} messages", count);
        long start = System.currentTimeMillis();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        CompletableFuture.runAsync(() -> {
            for (int i = 0; i < count; i++) {
                try {
                    kafkaProducerService.sendStockEventAsync(StockEventDto.create(
                            StockEventDto.EventType.CREATED, (long) (i + 1),
                            "LoadTest-" + i, 100, new BigDecimal("99.99")));
                    success.incrementAndGet();
                } catch (Exception e) {
                    failure.incrementAndGet();
                }
                if ((i + 1) % 1000 == 0) log.info("Progress: {}/{}", i + 1, count);
            }
        }).join();

        long duration = System.currentTimeMillis() - start;
        double tps = (count * 1000.0) / duration;

        Map<String, Object> result = Map.of(
                "totalMessages", count,
                "successCount",  success.get(),
                "failureCount",  failure.get(),
                "durationMs",    duration,
                "throughputMsgPerSec", Math.round(tps),
                "status", "completed");

        log.info("✅ Load test done: {} msg in {}ms ({} msg/s)", count, duration, Math.round(tps));
        return ResponseEntity.ok(result);
    }

    /** POST /api/load-test/kafka/batch?count=10000&batchSize=100&delayMs=10 */
    @PostMapping("/kafka/batch")
    public ResponseEntity<Map<String, Object>> kafkaBatchLoadTest(
            @RequestParam(defaultValue = "10000") int count,
            @RequestParam(defaultValue = "100") int batchSize,
            @RequestParam(defaultValue = "0") long delayMs) {

        log.info("🚀 Kafka batch load test: {} messages, batch={}, delay={}ms", count, batchSize, delayMs);
        long start = System.currentTimeMillis();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        int batches = (int) Math.ceil((double) count / batchSize);

        CompletableFuture.runAsync(() -> {
            for (int b = 0; b < batches; b++) {
                int from = b * batchSize;
                int to   = Math.min(from + batchSize, count);
                for (int i = from; i < to; i++) {
                    try {
                        kafkaProducerService.sendStockEventAsync(StockEventDto.create(
                                StockEventDto.EventType.CREATED, (long) (i + 1),
                                "Batch-" + i, 100, new BigDecimal("99.99")));
                        success.incrementAndGet();
                    } catch (Exception e) {
                        failure.incrementAndGet();
                    }
                }
                if (delayMs > 0 && b < batches - 1) {
                    try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
                log.info("Batch {}/{} done", b + 1, batches);
            }
        }).join();

        long duration = System.currentTimeMillis() - start;
        double tps = (count * 1000.0) / duration;

        Map<String, Object> result = new HashMap<>();
        result.put("totalMessages", count);
        result.put("batchSize",  batchSize);
        result.put("batches",    batches);
        result.put("successCount",  success.get());
        result.put("failureCount",  failure.get());
        result.put("durationMs",    duration);
        result.put("throughputMsgPerSec", Math.round(tps));
        result.put("status", "completed");

        log.info("✅ Batch test done: {} msg in {}ms ({} msg/s)", count, duration, Math.round(tps));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "LoadTestController"));
    }
}
