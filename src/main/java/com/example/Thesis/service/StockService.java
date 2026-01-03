package com.example.Thesis.service;

import com.example.Thesis.dto.StockEventDto;
import com.example.Thesis.dto.StockRequestDto;
import com.example.Thesis.dto.StockResponseDto;
import com.example.Thesis.model.Stock;
import com.example.Thesis.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockService {

    private final StockRepository stockRepository;
    private final KafkaProducerService kafkaProducerService;

    @Transactional
    public StockResponseDto createStock(StockRequestDto requestDto) {
        log.info("Creating new stock: productName={}", requestDto.getProductName());

        // Check if product already exists
        if (stockRepository.existsByProductName(requestDto.getProductName())) {
            throw new IllegalArgumentException(
                    "Product with name '" + requestDto.getProductName() + "' already exists");
        }

        // Create and save stock
        Stock stock = new Stock();
        stock.setProductName(requestDto.getProductName());
        stock.setQuantity(requestDto.getQuantity());
        stock.setPrice(requestDto.getPrice());

        Stock savedStock = stockRepository.save(stock);
        log.info("Stock created successfully: id={}, productName={}", savedStock.getId(), savedStock.getProductName());

        // Send Kafka event
        StockEventDto event = StockEventDto.create(
                StockEventDto.EventType.CREATED,
                savedStock.getId(),
                savedStock.getProductName(),
                savedStock.getQuantity(),
                savedStock.getPrice());
        kafkaProducerService.sendStockEvent(event);

        return mapToResponseDto(savedStock);
    }

    @Transactional(readOnly = true)
    public StockResponseDto getStockById(Long id) {
        log.info("Fetching stock by id: {}", id);
        Stock stock = stockRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Stock not found with id: " + id));
        return mapToResponseDto(stock);
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
        log.info("Updating stock: id={}, productName={}", id, requestDto.getProductName());

        Stock stock = stockRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Stock not found with id: " + id));

        // Check if new product name conflicts with another stock
        if (!stock.getProductName().equals(requestDto.getProductName()) &&
                stockRepository.existsByProductName(requestDto.getProductName())) {
            throw new IllegalArgumentException(
                    "Product with name '" + requestDto.getProductName() + "' already exists");
        }

        stock.setProductName(requestDto.getProductName());
        stock.setQuantity(requestDto.getQuantity());
        stock.setPrice(requestDto.getPrice());

        Stock updatedStock = stockRepository.save(stock);
        log.info("Stock updated successfully: id={}, productName={}", updatedStock.getId(),
                updatedStock.getProductName());

        // Send Kafka event
        StockEventDto event = StockEventDto.create(
                StockEventDto.EventType.UPDATED,
                updatedStock.getId(),
                updatedStock.getProductName(),
                updatedStock.getQuantity(),
                updatedStock.getPrice());
        kafkaProducerService.sendStockEvent(event);

        return mapToResponseDto(updatedStock);
    }

    @Transactional
    public void deleteStock(Long id) {
        log.info("Deleting stock: id={}", id);

        Stock stock = stockRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Stock not found with id: " + id));

        stockRepository.delete(stock);
        log.info("Stock deleted successfully: id={}, productName={}", stock.getId(), stock.getProductName());

        // Send Kafka event
        StockEventDto event = StockEventDto.create(
                StockEventDto.EventType.DELETED,
                stock.getId(),
                stock.getProductName(),
                stock.getQuantity(),
                stock.getPrice());
        kafkaProducerService.sendStockEvent(event);
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
