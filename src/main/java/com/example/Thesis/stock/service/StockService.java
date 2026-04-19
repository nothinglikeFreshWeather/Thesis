package com.example.Thesis.stock.service;

import com.example.Thesis.stock.dto.StockEventDto;
import com.example.Thesis.stock.dto.StockRequestDto;
import com.example.Thesis.stock.dto.StockResponseDto;
import com.example.Thesis.stock.model.Stock;
import com.example.Thesis.stock.outbox.OutboxEvent;
import com.example.Thesis.stock.outbox.OutboxEventStatus;
import com.example.Thesis.stock.repository.OutboxEventRepository;
import com.example.Thesis.stock.repository.StockRepository;
import com.example.Thesis.shared.cache.CacheService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockService {

    private final StockRepository stockRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final CacheService cacheService;

    // Metrics
    private final Timer stockCreateTimer;
    private final Timer stockGetTimer;
    private final Timer stockUpdateTimer;
    private final Timer stockDeleteTimer;

    private static final String CACHE_KEY_PREFIX = "stock:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    @Transactional
    public StockResponseDto createStock(StockRequestDto requestDto) {
        return stockCreateTimer.record(() -> {
            log.info("Creating stock: productName={}", requestDto.getProductName());

            if (stockRepository.existsByProductName(requestDto.getProductName())) {
                throw new IllegalArgumentException(
                        "Product '" + requestDto.getProductName() + "' already exists");
            }

            Stock stock = new Stock();
            stock.setProductName(requestDto.getProductName());
            stock.setQuantity(requestDto.getQuantity());
            stock.setPrice(requestDto.getPrice());

            Stock saved = stockRepository.save(stock);
            log.info("Stock saved: id={}", saved.getId());

            StockResponseDto response = toResponseDto(saved);
            cacheService.set(CACHE_KEY_PREFIX + saved.getId(), response, CACHE_TTL);

            // Write outbox — same TX, zero event loss if Kafka is partitioned
            saveToOutbox(StockEventDto.create(
                    StockEventDto.EventType.CREATED,
                    saved.getId(), saved.getProductName(), saved.getQuantity(), saved.getPrice()));

            return response;
        });
    }

    @Transactional(readOnly = true)
    public StockResponseDto getStockById(Long id) {
        return stockGetTimer.record(() -> {
            log.info("Fetching stock: id={}", id);
            String cacheKey = CACHE_KEY_PREFIX + id;
            Optional<StockResponseDto> cached = cacheService.get(cacheKey, StockResponseDto.class);
            if (cached.isPresent()) {
                log.debug("Cache HIT: id={}", id);
                return cached.get();
            }
            log.debug("Cache MISS: id={}", id);
            Stock stock = stockRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Stock not found: " + id));
            StockResponseDto response = toResponseDto(stock);
            cacheService.set(cacheKey, response, CACHE_TTL);
            return response;
        });
    }

    @Transactional(readOnly = true)
    public List<StockResponseDto> getAllStocks() {
        log.info("Fetching all stocks");
        return stockRepository.findAll().stream()
                .map(this::toResponseDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public StockResponseDto updateStock(Long id, StockRequestDto requestDto) {
        return stockUpdateTimer.record(() -> {
            log.info("Updating stock: id={}", id);

            Stock stock = stockRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Stock not found: " + id));

            if (!stock.getProductName().equals(requestDto.getProductName()) &&
                    stockRepository.existsByProductName(requestDto.getProductName())) {
                throw new IllegalArgumentException(
                        "Product '" + requestDto.getProductName() + "' already exists");
            }

            stock.setProductName(requestDto.getProductName());
            stock.setQuantity(requestDto.getQuantity());
            stock.setPrice(requestDto.getPrice());

            Stock updated = stockRepository.save(stock);
            StockResponseDto response = toResponseDto(updated);
            cacheService.set(CACHE_KEY_PREFIX + updated.getId(), response, CACHE_TTL);

            saveToOutbox(StockEventDto.create(
                    StockEventDto.EventType.UPDATED,
                    updated.getId(), updated.getProductName(), updated.getQuantity(), updated.getPrice()));

            return response;
        });
    }

    @Transactional
    public void deleteStock(Long id) {
        stockDeleteTimer.record(() -> {
            log.info("Deleting stock: id={}", id);
            Stock stock = stockRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Stock not found: " + id));

            stockRepository.delete(stock);
            cacheService.delete(CACHE_KEY_PREFIX + stock.getId());

            saveToOutbox(StockEventDto.create(
                    StockEventDto.EventType.DELETED,
                    stock.getId(), stock.getProductName(), stock.getQuantity(), stock.getPrice()));
            return null;
        });
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Persists a stock event to the outbox_events table within the active
     * {@code @Transactional} context. If serialization fails the transaction
     * rolls back — no orphaned stock record without an event.
     */
    private void saveToOutbox(StockEventDto event) {
        try {
            OutboxEvent outbox = new OutboxEvent();
            outbox.setEventId(event.getEventId());
            outbox.setEventType(event.getEventType().name());
            outbox.setStockId(event.getStockId());
            outbox.setPayload(objectMapper.writeValueAsString(event));
            outbox.setStatus(OutboxEventStatus.PENDING);
            outboxEventRepository.save(outbox);
            log.debug("[Outbox] Event queued: eventId={}, type={}", event.getEventId(), event.getEventType());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize stock event for outbox", e);
        }
    }

    private StockResponseDto toResponseDto(Stock stock) {
        return new StockResponseDto(
                stock.getId(), stock.getProductName(),
                stock.getQuantity(), stock.getPrice(),
                stock.getCreatedAt(), stock.getUpdatedAt());
    }
}
