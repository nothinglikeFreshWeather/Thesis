package com.example.Thesis.stock.repository;

import com.example.Thesis.stock.outbox.OutboxEvent;
import com.example.Thesis.stock.outbox.OutboxEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query("SELECT o FROM OutboxEvent o WHERE o.status = :status ORDER BY o.createdAt ASC LIMIT :limit")
    List<OutboxEvent> findPendingEvents(
            @Param("status") OutboxEventStatus status,
            @Param("limit") int limit);

    long countByStatus(OutboxEventStatus status);

    boolean existsByEventId(String eventId);
}
