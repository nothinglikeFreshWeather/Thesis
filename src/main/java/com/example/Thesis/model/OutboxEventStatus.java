package com.example.Thesis.model;

/**
 * Outbox olayının yaşam döngüsü durumları.
 *
 * <ul>
 *   <li>{@code PENDING}  – Veritabanına yazıldı, henüz Kafka'ya gönderilmedi.</li>
 *   <li>{@code SENT}     – Kafka'ya başarıyla iletildi (silindi değil, arşiv amaçlı tutulur).</li>
 *   <li>{@code FAILED}   – Maksimum yeniden deneme sayısı aşıldı, manuel müdahale gerekebilir.</li>
 * </ul>
 */
public enum OutboxEventStatus {
    PENDING,
    SENT,
    FAILED
}
