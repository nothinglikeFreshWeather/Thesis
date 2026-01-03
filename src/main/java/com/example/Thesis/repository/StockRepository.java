package com.example.Thesis.repository;

import com.example.Thesis.model.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StockRepository extends JpaRepository<Stock, Long> {

    Optional<Stock> findByProductName(String productName);

    boolean existsByProductName(String productName);
}
