package com.example.Thesis.stock.outbox;

import com.example.Thesis.stock.dto.StockEventDto;
import com.example.Thesis.stock.repository.OutboxEventRepository;
import com.example.Thesis.stock.service.KafkaProducerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox Pattern Scheduler.
 *
 * <p>Polls {@code outbox_events} for PENDING records every 5 s and forwards
 * them to Kafka via {@link KafkaProducerService#sendStockEventSync}.</p>
 *
 * <ul>
 *   <li>Success → status = SENT</li>
 *   <li>Failure → retryCount++; when retryCount >= maxRetries → status = FAILED</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxScheduler {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaProducerService kafkaProducerService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Value("${outbox.scheduler.batch-size:50}")
    private int batchSize;

    @Value("${outbox.scheduler.max-retries:5}")
    private int maxRetries;

    private Counter outboxSentCounter;
    private Counter outboxFailedCounter;
    private Counter outboxRetriedCounter;

    @jakarta.annotation.PostConstruct
    public void initMetrics() {
        outboxSentCounter = Counter.builder("outbox_events_total")
                .tag("result", "sent")
                .description("Outbox events successfully forwarded to Kafka")
                .register(meterRegistry);

        outboxFailedCounter = Counter.builder("outbox_events_total")
                .tag("result", "failed")
                .description("Outbox events that exceeded max retries")
                .register(meterRegistry);

        outboxRetriedCounter = Counter.builder("outbox_events_total")
                .tag("result", "retried")
                .description("Outbox events that were retried")
                .register(meterRegistry);

        Gauge.builder("outbox_pending_events", outboxEventRepository,
                        repo -> repo.countByStatus(OutboxEventStatus.PENDING))
                .description("Number of outbox events waiting for Kafka delivery")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${outbox.scheduler.fixed-delay-ms:5000}")
    public void processOutboxEvents() {
        List<OutboxEvent> pending = outboxEventRepository
                .findPendingEvents(OutboxEventStatus.PENDING, batchSize);

        if (pending.isEmpty()) return;

        log.info("[Outbox] Processing {} pending event(s).", pending.size());
        for (OutboxEvent event : pending) {
            processEvent(event);
        }
    }

    @Transactional
    protected void processEvent(OutboxEvent outboxEvent) {
        try {
            StockEventDto dto = objectMapper.readValue(outboxEvent.getPayload(), StockEventDto.class);
            kafkaProducerService.sendStockEventSync(dto);

            outboxEvent.setStatus(OutboxEventStatus.SENT);
            outboxEvent.setProcessedAt(LocalDateTime.now());
            outboxEventRepository.save(outboxEvent);
            outboxSentCounter.increment();

            log.info("[Outbox] Sent: eventId={}, type={}", outboxEvent.getEventId(), outboxEvent.getEventType());

        } catch (Exception e) {
            int retries = outboxEvent.getRetryCount() + 1;
            outboxEvent.setRetryCount(retries);
            outboxEvent.setLastError(truncate(e.getMessage(), 500));
            outboxEvent.setProcessedAt(LocalDateTime.now());

            if (retries >= maxRetries) {
                outboxEvent.setStatus(OutboxEventStatus.FAILED);
                outboxFailedCounter.increment();
                log.error("[Outbox] FAILED after {} retries: eventId={}", retries, outboxEvent.getEventId());
            } else {
                outboxRetriedCounter.increment();
                log.warn("[Outbox] Retry {}/{}: eventId={}, error={}",
                        retries, maxRetries, outboxEvent.getEventId(), e.getMessage());
            }
            outboxEventRepository.save(outboxEvent);
        }
    }

    private String truncate(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }
}
