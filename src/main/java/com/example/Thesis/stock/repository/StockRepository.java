package com.example.Thesis.stock.repository;

import com.example.Thesis.stock.model.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StockRepository extends JpaRepository<Stock, Long> {
    boolean existsByProductName(String productName);
}
