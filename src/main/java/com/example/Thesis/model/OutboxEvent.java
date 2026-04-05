package com.example.Thesis.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Outbox Pattern implementation entity.
 *
 * <p>Stok işlemleri tamamlandığında Kafka'ya direkt göndermek yerine bu tabloya
 * yazılır. Arka planda çalışan OutboxScheduler periyodik olarak bu tabloyu
 * tarar ve bekleyen olayları Kafka'ya iletir. Böylece Kafka geçici olarak
 * erişilemez olsa bile hiçbir olay kaybedilmez.</p>
 */
@Entity
@Table(
    name = "outbox_events",
    indexes = {
        @Index(name = "idx_outbox_status_created", columnList = "status, createdAt"),
        @Index(name = "idx_outbox_event_id",       columnList = "eventId", unique = true)
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** StockEventDto.eventId — idempotency için UUID */
    @Column(nullable = false, unique = true, length = 36)
    private String eventId;

    /** CREATED / UPDATED / DELETED */
    @Column(nullable = false, length = 20)
    private String eventType;

    /** İlgili stok kaydının id'si */
    @Column(nullable = false)
    private Long stockId;

    /** JSON olarak serileştirilmiş StockEventDto payload'u */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    /** Mevcut durum: PENDING → SENT | FAILED */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OutboxEventStatus status = OutboxEventStatus.PENDING;

    /** Kaç kez gönderilmeye çalışıldı */
    @Column(nullable = false)
    private int retryCount = 0;

    /** Son hata mesajı (debug için) */
    @Column(length = 500)
    private String lastError;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** En son gönderim denemesinin zamanı */
    @Column
    private LocalDateTime processedAt;
}
