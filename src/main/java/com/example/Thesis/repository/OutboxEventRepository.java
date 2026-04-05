package com.example.Thesis.repository;

import com.example.Thesis.model.OutboxEvent;
import com.example.Thesis.model.OutboxEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Scheduler tarafından işlenmeyi bekleyen olayları sırayla (FIFO) getirir.
     * Yüksek yük altında tüm tabloyu taramaktan kaçınmak için limit uygulanır.
     */
    @Query("SELECT o FROM OutboxEvent o WHERE o.status = :status ORDER BY o.createdAt ASC LIMIT :limit")
    List<OutboxEvent> findPendingEvents(
            @Param("status") OutboxEventStatus status,
            @Param("limit") int limit);

    /** Belirtilen durumdaki toplam kayıt sayısı – metrik amaçlı */
    long countByStatus(OutboxEventStatus status);

    /** Idempotency kontrolü: aynı eventId daha önce işlendi mi? */
    boolean existsByEventId(String eventId);
}
