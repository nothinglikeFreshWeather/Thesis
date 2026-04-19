package com.example.Thesis.stock.outbox;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Outbox Pattern entity.
 *
 * <p>Stock mutations write an event here (same transaction). The
 * {@link OutboxScheduler} polls this table every 5 s and forwards
 * PENDING events to Kafka with automatic retries.</p>
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

    @Column(nullable = false, unique = true, length = 36)
    private String eventId;

    @Column(nullable = false, length = 20)
    private String eventType;

    @Column(nullable = false)
    private Long stockId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OutboxEventStatus status = OutboxEventStatus.PENDING;

    @Column(nullable = false)
    private int retryCount = 0;

    @Column(length = 500)
    private String lastError;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime processedAt;
}
