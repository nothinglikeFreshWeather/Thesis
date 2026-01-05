package com.example.Thesis.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    // Redis Cache Metrics
    @Bean
    public Counter cacheHitCounter(MeterRegistry registry) {
        return Counter.builder("cache_hit_miss_total")
                .tag("result", "hit")
                .description("Total number of cache hits")
                .register(registry);
    }

    @Bean
    public Counter cacheMissCounter(MeterRegistry registry) {
        return Counter.builder("cache_hit_miss_total")
                .tag("result", "miss")
                .description("Total number of cache misses")
                .register(registry);
    }

    @Bean
    public Timer cacheGetTimer(MeterRegistry registry) {
        return Timer.builder("cache_operation_duration_seconds")
                .tag("operation", "get")
                .description("Cache get operation duration")
                .register(registry);
    }

    @Bean
    public Timer cacheSetTimer(MeterRegistry registry) {
        return Timer.builder("cache_operation_duration_seconds")
                .tag("operation", "set")
                .description("Cache set operation duration")
                .register(registry);
    }

    @Bean
    public Timer cacheDeleteTimer(MeterRegistry registry) {
        return Timer.builder("cache_operation_duration_seconds")
                .tag("operation", "delete")
                .description("Cache delete operation duration")
                .register(registry);
    }

    @Bean
    public Counter redisReconnectCounter(MeterRegistry registry) {
        return Counter.builder("redis_reconnect_total")
                .description("Total number of Redis reconnection attempts")
                .register(registry);
    }

    // Kafka Producer Metrics
    @Bean
    public Counter kafkaProducerSuccessCounter(MeterRegistry registry) {
        return Counter.builder("kafka_producer_send_total")
                .tag("status", "success")
                .description("Total number of successful Kafka messages sent")
                .register(registry);
    }

    @Bean
    public Counter kafkaProducerFailureCounter(MeterRegistry registry) {
        return Counter.builder("kafka_producer_send_total")
                .tag("status", "failure")
                .description("Total number of failed Kafka messages")
                .register(registry);
    }

    // Stock Service Metrics
    @Bean
    public Timer stockCreateTimer(MeterRegistry registry) {
        return Timer.builder("stock_operation_duration_seconds")
                .tag("operation", "create")
                .description("Stock creation operation duration")
                .register(registry);
    }

    @Bean
    public Timer stockGetTimer(MeterRegistry registry) {
        return Timer.builder("stock_operation_duration_seconds")
                .tag("operation", "get")
                .description("Stock get operation duration")
                .register(registry);
    }

    @Bean
    public Timer stockUpdateTimer(MeterRegistry registry) {
        return Timer.builder("stock_operation_duration_seconds")
                .tag("operation", "update")
                .description("Stock update operation duration")
                .register(registry);
    }

    @Bean
    public Timer stockDeleteTimer(MeterRegistry registry) {
        return Timer.builder("stock_operation_duration_seconds")
                .tag("operation", "delete")
                .description("Stock delete operation duration")
                .register(registry);
    }
}
