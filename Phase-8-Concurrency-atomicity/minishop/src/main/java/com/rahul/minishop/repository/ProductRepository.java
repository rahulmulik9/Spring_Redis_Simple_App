package com.rahul.minishop.repository;

import com.rahul.minishop.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {
    @Modifying
    @Query("UPDATE Product p SET p.stock = p.stock - 1 WHERE p.id = :id AND p.stock > 0")
    int decrementStockIfAvailable(@Param("id") Long id);
}