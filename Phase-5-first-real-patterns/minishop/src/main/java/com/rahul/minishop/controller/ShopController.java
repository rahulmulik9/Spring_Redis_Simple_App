package com.rahul.minishop.controller;

import com.rahul.minishop.entity.Product;
import com.rahul.minishop.service.ShopService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ShopController {

    private final ShopService shopService;


    @GetMapping("/{id}")
    public Product getProduct(@PathVariable Long id, @RequestParam(required = false) Long userId) {
        if (userId != null) {
            shopService.addView(id);                       //add into view this will increase view count for product
            shopService.addToRecentlyViewed(userId, id);   //This will save into recently viewed item
            shopService.incrementProductScore(id);         //THis will be sued to get maximum viewed product
        }
        return shopService.getProduct(id);
    }


    @PutMapping("/{id}")
    public Product updateProduct(@PathVariable Long id, @RequestBody Product product) {
        return shopService.updateProduct(id, product);
    }


    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        shopService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public List<Product> getAllProducts() {
        return shopService.getAllProducts();
    }

    //controller to get count
    @PostMapping("/{id}/view")
    public Long addView(@PathVariable Long id) {
        return shopService.addView(id);
    }

    @GetMapping("/top")
    public Set<ZSetOperations.TypedTuple<String>> getTopProducts(@RequestParam(defaultValue = "5") int limit) {
        return shopService.getTopProducts(limit);
    }

}