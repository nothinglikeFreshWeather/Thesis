package com.example.Thesis.service;

import com.example.Thesis.dto.StockEventDto;
import com.example.Thesis.dto.StockRequestDto;
import com.example.Thesis.dto.StockResponseDto;
import com.example.Thesis.model.OutboxEvent;
import com.example.Thesis.model.OutboxEventStatus;
import com.example.Thesis.model.Stock;
import com.example.Thesis.repository.OutboxEventRepository;
import com.example.Thesis.repository.StockRepository;
import com.example.Thesis.service.cache.CacheService;
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
                        log.info("Creating new stock: productName={}", requestDto.getProductName());

                        // Check if product already exists
                        if (stockRepository.existsByProductName(requestDto.getProductName())) {
                                throw new IllegalArgumentException(
                                                "Product with name '" + requestDto.getProductName()
                                                                + "' already exists");
                        }

                        // Create and save stock
                        Stock stock = new Stock();
                        stock.setProductName(requestDto.getProductName());
                        stock.setQuantity(requestDto.getQuantity());
                        stock.setPrice(requestDto.getPrice());

                        Stock savedStock = stockRepository.save(stock);
                        log.info("Stock created successfully: id={}, productName={}", savedStock.getId(),
                                        savedStock.getProductName());

                        StockResponseDto response = mapToResponseDto(savedStock);

                        // Cache the new stock (write-through)
                        String cacheKey = CACHE_KEY_PREFIX + savedStock.getId();
                        cacheService.set(cacheKey, response, CACHE_TTL);
                        log.debug("Cached new stock: key={}", cacheKey);

                        // Write to Outbox — same transaction as DB save.
                        // OutboxScheduler will deliver to Kafka with retries.
                        StockEventDto event = StockEventDto.create(
                                        StockEventDto.EventType.CREATED,
                                        savedStock.getId(),
                                        savedStock.getProductName(),
                                        savedStock.getQuantity(),
                                        savedStock.getPrice());
                        saveToOutbox(event);

                        return response;
                });
        }

        @Transactional(readOnly = true)
        public StockResponseDto getStockById(Long id) {
                return stockGetTimer.record(() -> {
                        log.info("Fetching stock by id: {}", id);

                        // Try cache first (read-through from Redis replica)
                        String cacheKey = CACHE_KEY_PREFIX + id;
                        Optional<StockResponseDto> cached = cacheService.get(cacheKey, StockResponseDto.class);

                        if (cached.isPresent()) {
                                log.info("Cache HIT for stock: {}", id);
                                return cached.get();
                        }

                        // Cache miss - read from database
                        log.info("Cache MISS for stock: {}", id);
                        Stock stock = stockRepository.findById(id)
                                        .orElseThrow(() -> new IllegalArgumentException(
                                                        "Stock not found with id: " + id));

                        StockResponseDto response = mapToResponseDto(stock);

                        // Cache the result (write to Redis master)
                        cacheService.set(cacheKey, response, CACHE_TTL);
                        log.debug("Cached stock from DB: key={}", cacheKey);

                        return response;
                });
        }

        @Transactional(readOnly = true)
        public List<StockResponseDto> getAllStocks() {
                log.info("Fetching all stocks");
                return stockRepository.findAll().stream()
                                .map(this::mapToResponseDto)
                                .collect(Collectors.toList());
        }

        @Transactional
        public StockResponseDto updateStock(Long id, StockRequestDto requestDto) {
                return stockUpdateTimer.record(() -> {
                        log.info("Updating stock: id={}, productName={}", id, requestDto.getProductName());

                        Stock stock = stockRepository.findById(id)
                                        .orElseThrow(() -> new IllegalArgumentException(
                                                        "Stock not found with id: " + id));

                        // Check if new product name conflicts with another stock
                        if (!stock.getProductName().equals(requestDto.getProductName()) &&
                                        stockRepository.existsByProductName(requestDto.getProductName())) {
                                throw new IllegalArgumentException(
                                                "Product with name '" + requestDto.getProductName()
                                                                + "' already exists");
                        }

                        stock.setProductName(requestDto.getProductName());
                        stock.setQuantity(requestDto.getQuantity());
                        stock.setPrice(requestDto.getPrice());

                        Stock updatedStock = stockRepository.save(stock);
                        log.info("Stock updated successfully: id={}, productName={}", updatedStock.getId(),
                                        updatedStock.getProductName());

                        StockResponseDto response = mapToResponseDto(updatedStock);

                        // Update cache (write-through)
                        String cacheKey = CACHE_KEY_PREFIX + updatedStock.getId();
                        cacheService.set(cacheKey, response, CACHE_TTL);
                        log.debug("Updated cache for stock: key={}", cacheKey);

                        // Write to Outbox — same transaction as DB update.
                        StockEventDto event = StockEventDto.create(
                                        StockEventDto.EventType.UPDATED,
                                        updatedStock.getId(),
                                        updatedStock.getProductName(),
                                        updatedStock.getQuantity(),
                                        updatedStock.getPrice());
                        saveToOutbox(event);

                        return response;
                });
        }

        @Transactional
        public void deleteStock(Long id) {
                stockDeleteTimer.record(() -> {
                        log.info("Deleting stock: id={}", id);

                        Stock stock = stockRepository.findById(id)
                                        .orElseThrow(() -> new IllegalArgumentException(
                                                        "Stock not found with id: " + id));

                        stockRepository.delete(stock);
                        log.info("Stock deleted successfully: id={}, productName={}", stock.getId(),
                                        stock.getProductName());

                        // Invalidate cache
                        String cacheKey = CACHE_KEY_PREFIX + stock.getId();
                        cacheService.delete(cacheKey);
                        log.debug("Invalidated cache for deleted stock: key={}", cacheKey);

                        // Write to Outbox — same transaction as DB delete.
                        StockEventDto event = StockEventDto.create(
                                        StockEventDto.EventType.DELETED,
                                        stock.getId(),
                                        stock.getProductName(),
                                        stock.getQuantity(),
                                        stock.getPrice());
                        saveToOutbox(event);
                        return null;
                });
        }

        /**
         * Writes a stock event into the outbox_events table.
         * Called within an active @Transactional context so the outbox insert
         * is atomic with the stock mutation — if either fails, both roll back.
         */
        private void saveToOutbox(StockEventDto event) {
                try {
                        String payload = objectMapper.writeValueAsString(event);
                        OutboxEvent outboxEvent = new OutboxEvent();
                        outboxEvent.setEventId(event.getEventId());
                        outboxEvent.setEventType(event.getEventType().name());
                        outboxEvent.setStockId(event.getStockId());
                        outboxEvent.setPayload(payload);
                        outboxEvent.setStatus(OutboxEventStatus.PENDING);
                        outboxEventRepository.save(outboxEvent);
                        log.debug("[Outbox] Event saved: eventId={}, type={}", event.getEventId(), event.getEventType());
                } catch (JsonProcessingException e) {
                        // This should never happen with a well-formed DTO.
                        // Throwing here causes the parent @Transactional to roll back,
                        // so the stock mutation is also undone — data stays consistent.
                        throw new RuntimeException("Failed to serialize stock event for outbox", e);
                }
        }

        private StockResponseDto mapToResponseDto(Stock stock) {
                return new StockResponseDto(
                                stock.getId(),
                                stock.getProductName(),
                                stock.getQuantity(),
                                stock.getPrice(),
                                stock.getCreatedAt(),
                                stock.getUpdatedAt());
        }
}

