package com.example.Thesis.controller;

import com.example.Thesis.dto.StockRequestDto;
import com.example.Thesis.dto.StockResponseDto;
import com.example.Thesis.service.StockService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController 
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
@Slf4j
public class StockController {

    private final StockService stockService;

    @PostMapping
    public ResponseEntity<StockResponseDto> createStock(@Valid @RequestBody StockRequestDto requestDto) {
        log.info("Received request to create stock: {}", requestDto.getProductName());
        StockResponseDto response = stockService.createStock(requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<StockResponseDto> getStock(@PathVariable Long id) {
        log.info("Received request to get stock: id={}", id);
        StockResponseDto response = stockService.getStockById(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<StockResponseDto>> getAllStocks() {
        log.info("Received request to get all stocks");
        List<StockResponseDto> response = stockService.getAllStocks();
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<StockResponseDto> updateStock(
            @PathVariable Long id,
            @Valid @RequestBody StockRequestDto requestDto) {
        log.info("Received request to update stock: id={}", id);
        StockResponseDto response = stockService.updateStock(id, requestDto);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteStock(@PathVariable Long id) {
        log.info("Received request to delete stock: id={}", id);
        stockService.deleteStock(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.error("Validation error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("An unexpected error occurred"));
    }

    // Error response DTO
    record ErrorResponse(String message) {
    }
}
