package com.example.Thesis.stock.controller;

import com.example.Thesis.stock.dto.StockRequestDto;
import com.example.Thesis.stock.dto.StockResponseDto;
import com.example.Thesis.stock.service.StockService;
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
        log.info("Create stock request: {}", requestDto.getProductName());
        return ResponseEntity.status(HttpStatus.CREATED).body(stockService.createStock(requestDto));
    }

    @GetMapping("/{id}")
    public ResponseEntity<StockResponseDto> getStock(@PathVariable Long id) {
        return ResponseEntity.ok(stockService.getStockById(id));
    }

    @GetMapping
    public ResponseEntity<List<StockResponseDto>> getAllStocks() {
        return ResponseEntity.ok(stockService.getAllStocks());
    }

    @PutMapping("/{id}")
    public ResponseEntity<StockResponseDto> updateStock(
            @PathVariable Long id, @Valid @RequestBody StockRequestDto requestDto) {
        return ResponseEntity.ok(stockService.updateStock(id, requestDto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteStock(@PathVariable Long id) {
        stockService.deleteStock(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.error("Validation error: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.internalServerError().body(new ErrorResponse("An unexpected error occurred"));
    }

    record ErrorResponse(String message) {}
}
