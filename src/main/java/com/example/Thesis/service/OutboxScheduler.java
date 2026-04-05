package com.example.Thesis.service;

import com.example.Thesis.dto.StockEventDto;
import com.example.Thesis.model.OutboxEvent;
import com.example.Thesis.model.OutboxEventStatus;
import com.example.Thesis.repository.OutboxEventRepository;
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
 * <p>Periyodik olarak {@code outbox_events} tablosundaki {@code PENDING} kayıtları
 * sorgular ve bunları Kafka'ya iletmeye çalışır.</p>
 *
 * <ul>
 *   <li>Başarı → kaydı {@code SENT} olarak işaretler.</li>
 *   <li>Hata   → {@code retryCount}'u artırır; {@code maxRetries} aşılırsa {@code FAILED} yapar.</li>
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

    // ── Metrics ──────────────────────────────────────────────────────────────

    private Counter outboxSentCounter;
    private Counter outboxFailedCounter;
    private Counter outboxRetriedCounter;

    /** Spring beanları hazır olduktan sonra metric'leri kaydet. */
    @jakarta.annotation.PostConstruct
    public void initMetrics() {
        outboxSentCounter = Counter.builder("outbox_events_total")
                .tag("result", "sent")
                .description("Outbox'tan Kafka'ya başarıyla gönderilen toplam olay sayısı")
                .register(meterRegistry);

        outboxFailedCounter = Counter.builder("outbox_events_total")
                .tag("result", "failed")
                .description("Maksimum deneme sayısını aşan ve FAILED olan toplam olay sayısı")
                .register(meterRegistry);

        outboxRetriedCounter = Counter.builder("outbox_events_total")
                .tag("result", "retried")
                .description("Yeniden denenen toplam olay sayısı")
                .register(meterRegistry);

        // Anlık PENDING sayısı — Prometheus gauge
        Gauge.builder("outbox_pending_events", outboxEventRepository,
                        repo -> repo.countByStatus(OutboxEventStatus.PENDING))
                .description("Şu an Kafka'ya gönderilmeyi bekleyen outbox olay sayısı")
                .register(meterRegistry);
    }

    // ── Scheduler ────────────────────────────────────────────────────────────

    /**
     * Her 5 saniyede bir çalışır (uygulama ayakta olduğu sürece).
     * {@code fixedDelay} → önceki çalışma bitmeden yeni işlem başlamaz.
     */
    @Scheduled(fixedDelayString = "${outbox.scheduler.fixed-delay-ms:5000}")
    public void processOutboxEvents() {
        List<OutboxEvent> pending = outboxEventRepository
                .findPendingEvents(OutboxEventStatus.PENDING, batchSize);

        if (pending.isEmpty()) {
            return;
        }

        log.info("[Outbox] {} adet bekleyen olay işlenecek.", pending.size());

        for (OutboxEvent outboxEvent : pending) {
            processEvent(outboxEvent);
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    @Transactional
    protected void processEvent(OutboxEvent outboxEvent) {
        try {
            StockEventDto dto = objectMapper.readValue(outboxEvent.getPayload(), StockEventDto.class);

            // Kafka'ya gönder (senkron tamamlanmasını bekle)
            kafkaProducerService.sendStockEventSync(dto);

            // Başarı: SENT olarak işaretle
            outboxEvent.setStatus(OutboxEventStatus.SENT);
            outboxEvent.setProcessedAt(LocalDateTime.now());
            outboxEventRepository.save(outboxEvent);

            outboxSentCounter.increment();
            log.info("[Outbox] Olay başarıyla Kafka'ya iletildi: eventId={}, type={}",
                    outboxEvent.getEventId(), outboxEvent.getEventType());

        } catch (Exception e) {
            int newRetryCount = outboxEvent.getRetryCount() + 1;
            outboxEvent.setRetryCount(newRetryCount);
            outboxEvent.setLastError(truncate(e.getMessage(), 500));
            outboxEvent.setProcessedAt(LocalDateTime.now());

            if (newRetryCount >= maxRetries) {
                outboxEvent.setStatus(OutboxEventStatus.FAILED);
                outboxFailedCounter.increment();
                log.error("[Outbox] Olay {} deneme sonrası FAILED: eventId={}, hata={}",
                        newRetryCount, outboxEvent.getEventId(), e.getMessage());
            } else {
                outboxRetriedCounter.increment();
                log.warn("[Outbox] Olay gönderilemedi (deneme {}/{}): eventId={}, hata={}",
                        newRetryCount, maxRetries, outboxEvent.getEventId(), e.getMessage());
            }

            outboxEventRepository.save(outboxEvent);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
