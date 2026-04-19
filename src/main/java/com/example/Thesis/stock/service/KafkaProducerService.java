package com.example.Thesis.stock.service;

import com.example.Thesis.stock.dto.StockEventDto;
import io.micrometer.core.instrument.Counter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaProducerService {

    private final KafkaTemplate<String, StockEventDto> kafkaTemplate;
    private final Counter kafkaProducerSuccessCounter;
    private final Counter kafkaProducerFailureCounter;

    @Value("${spring.kafka.topic.stock-events}")
    private String stockEventsTopic;

    /**
     * Synchronous send — used exclusively by {@link OutboxScheduler}.
     * Blocks until the broker acknowledges or throws an exception.
     * The exception propagates to the scheduler's retry logic.
     */
    public void sendStockEventSync(StockEventDto event) {
        log.info("[Outbox→Kafka] Sending: eventId={}, type={}", event.getEventId(), event.getEventType());
        try {
            SendResult<String, StockEventDto> result = kafkaTemplate
                    .send(stockEventsTopic, event.getStockId().toString(), event)
                    .get();

            kafkaProducerSuccessCounter.increment();
            log.info("[Outbox→Kafka] Sent: eventId={}, offset={}, partition={}",
                    event.getEventId(),
                    result.getRecordMetadata().offset(),
                    result.getRecordMetadata().partition());
        } catch (Exception e) {
            kafkaProducerFailureCounter.increment();
            log.error("[Outbox→Kafka] Failed: eventId={}, error={}", event.getEventId(), e.getMessage(), e);
            throw new RuntimeException("Kafka send failed for eventId=" + event.getEventId(), e);
        }
    }

    /**
     * Async fire-and-forget — kept for load-test endpoints that don't need
     * durability guarantees.
     */
    public CompletableFuture<SendResult<String, StockEventDto>> sendStockEventAsync(StockEventDto event) {
        log.info("Async Kafka send: eventId={}, type={}", event.getEventId(), event.getEventType());
        CompletableFuture<SendResult<String, StockEventDto>> future =
                kafkaTemplate.send(stockEventsTopic, event.getStockId().toString(), event);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                kafkaProducerSuccessCounter.increment();
                log.info("Async send OK: eventId={}, offset={}", event.getEventId(),
                        result.getRecordMetadata().offset());
            } else {
                kafkaProducerFailureCounter.increment();
                log.error("Async send FAILED: eventId={}, error={}", event.getEventId(), ex.getMessage(), ex);
            }
        });
        return future;
    }
}
