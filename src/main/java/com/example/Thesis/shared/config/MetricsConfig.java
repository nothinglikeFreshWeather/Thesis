package com.example.Thesis.shared.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    // ── Cache ─────────────────────────────────────────────────────────────────

    @Bean public Counter cacheHitCounter(MeterRegistry r) {
        return Counter.builder("cache_hit_miss_total").tag("result", "hit").register(r);
    }
    @Bean public Counter cacheMissCounter(MeterRegistry r) {
        return Counter.builder("cache_hit_miss_total").tag("result", "miss").register(r);
    }
    @Bean public Timer cacheGetTimer(MeterRegistry r) {
        return Timer.builder("cache_operation_duration_seconds").tag("operation", "get").register(r);
    }
    @Bean public Timer cacheSetTimer(MeterRegistry r) {
        return Timer.builder("cache_operation_duration_seconds").tag("operation", "set").register(r);
    }
    @Bean public Timer cacheDeleteTimer(MeterRegistry r) {
        return Timer.builder("cache_operation_duration_seconds").tag("operation", "delete").register(r);
    }
    @Bean public Counter redisReconnectCounter(MeterRegistry r) {
        return Counter.builder("redis_reconnect_total").register(r);
    }

    // ── Kafka Producer ────────────────────────────────────────────────────────

    @Bean public Counter kafkaProducerSuccessCounter(MeterRegistry r) {
        return Counter.builder("kafka_producer_send_total").tag("status", "success").register(r);
    }
    @Bean public Counter kafkaProducerFailureCounter(MeterRegistry r) {
        return Counter.builder("kafka_producer_send_total").tag("status", "failure").register(r);
    }

    // ── Stock Operations ──────────────────────────────────────────────────────

    @Bean public Timer stockCreateTimer(MeterRegistry r) {
        return Timer.builder("stock_operation_duration_seconds").tag("operation", "create").register(r);
    }
    @Bean public Timer stockGetTimer(MeterRegistry r) {
        return Timer.builder("stock_operation_duration_seconds").tag("operation", "get").register(r);
    }
    @Bean public Timer stockUpdateTimer(MeterRegistry r) {
        return Timer.builder("stock_operation_duration_seconds").tag("operation", "update").register(r);
    }
    @Bean public Timer stockDeleteTimer(MeterRegistry r) {
        return Timer.builder("stock_operation_duration_seconds").tag("operation", "delete").register(r);
    }

    // ── Rate Limiting ─────────────────────────────────────────────────────────

    @Bean public Counter rateLimitExceededCounter(MeterRegistry r) {
        return Counter.builder("rate_limit_exceeded_total").register(r);
    }

    // ── IoT Sensor ────────────────────────────────────────────────────────────

    @Bean public Counter warehouseSensorReceivedCounter(MeterRegistry r) {
        return Counter.builder("warehouse_sensor_received_total").register(r);
    }
    @Bean public Counter warehouseSensorAlertCounter(MeterRegistry r) {
        return Counter.builder("warehouse_sensor_alert_total").register(r);
    }
    @Bean public Counter warehouseSensorErrorCounter(MeterRegistry r) {
        return Counter.builder("warehouse_sensor_error_total").register(r);
    }
}
