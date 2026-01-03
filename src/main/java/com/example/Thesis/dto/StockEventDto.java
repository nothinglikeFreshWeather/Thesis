package com.example.Thesis.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockEventDto {

    private String eventId;
    private EventType eventType;
    private Long stockId;
    private String productName;
    private Integer quantity;
    private BigDecimal price;
    private LocalDateTime timestamp;

    public enum EventType {
        CREATED,
        UPDATED,
        DELETED
    }

    public static StockEventDto create(EventType eventType, Long stockId, String productName, Integer quantity,
            BigDecimal price) {
        StockEventDto event = new StockEventDto();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType(eventType);
        event.setStockId(stockId);
        event.setProductName(productName);
        event.setQuantity(quantity);
        event.setPrice(price);
        event.setTimestamp(LocalDateTime.now());
        return event;
    }
}
