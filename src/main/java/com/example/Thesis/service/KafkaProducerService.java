package com.example.Thesis.service;

import com.example.Thesis.dto.StockEventDto;
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

    @Value("${spring.kafka.topic.stock-events}")
    private String stockEventsTopic;

    public void sendStockEvent(StockEventDto event) {
        log.info("Attempting to send stock event to Kafka: eventId={}, type={}, product={}",
                event.getEventId(), event.getEventType(), event.getProductName());

        try {
            CompletableFuture<SendResult<String, StockEventDto>> future = kafkaTemplate.send(stockEventsTopic,
                    event.getStockId().toString(), event);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("Stock event sent successfully: eventId={}, offset={}, partition={}",
                            event.getEventId(),
                            result.getRecordMetadata().offset(),
                            result.getRecordMetadata().partition());
                } else {
                    log.error("Failed to send stock event: eventId={}, error={}",
                            event.getEventId(), ex.getMessage(), ex);
                }
            });
        } catch (Exception e) {
            log.error("Exception while sending stock event: eventId={}, error={}",
                    event.getEventId(), e.getMessage(), e);
            throw new RuntimeException("Failed to send Kafka message", e);
        }
    }
}
